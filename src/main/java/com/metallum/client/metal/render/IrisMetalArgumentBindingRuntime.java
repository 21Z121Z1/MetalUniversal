package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Optional diagnostics mirror for Iris resource-binding mutations.
 *
 * <p>This class is deliberately not the argument-table execution authority.
 * The real render path already batches admitted Java state through
 * {@code MetalRenderStatePacket}; on Metal 4 the native render bridge owns one
 * vertex and one fragment {@code MTL4ArgumentTable} per in-flight slot, binds
 * each table once when the encoder is created, and patches those tables from
 * the packet/setter stream. Keeping this WeakHashMap/reflection snapshot active
 * under the performance {@code argumentTables} flag therefore duplicates
 * bookkeeping without replacing any native binding.</p>
 *
 * <p>The mirror remains available behind the explicit
 * {@code metallum.iris.argumentSnapshotDiagnostics} switch for differential
 * diagnostics only. Patch snapshots are likewise diagnostic receipts; they do
 * not mutate native state and must never be accepted as performance-lane
 * admission evidence.</p>
 */
public final class IrisMetalArgumentBindingRuntime {
    private static final boolean SNAPSHOT_DIAGNOSTICS =
            Boolean.getBoolean("metallum.iris.argumentSnapshotDiagnostics");
    private static final Map<Object, State> PASSES = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final LongAdder LAYOUTS = new LongAdder();
    private static final LongAdder UPDATES = new LongAdder();
    private static final LongAdder ENCODED = new LongAdder();
    private static final LongAdder PATCH_SNAPSHOTS = new LongAdder();
    private static final LongAdder PATCH_ENTRIES = new LongAdder();
    private static final LongAdder PATCH_REJECTIONS = new LongAdder();

    private IrisMetalArgumentBindingRuntime() {
    }

    public static void attachPipeline(final Object pass) {
        if (!enabled() || pass == null) return;
        try {
            Object pipeline = field(pass, "compiledPipeline");
            if (pipeline == null) return;
            IrisMetalOptimizationPlan.ArgumentLayout layout = layout(pipeline);
            synchronized (PASSES) {
                State previous = PASSES.get(pass);
                if (previous == null || previous.layout.stableHash() != layout.stableHash()) {
                    PASSES.put(pass, new State(layout));
                    LAYOUTS.increment();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Metallum.LOGGER.warn("[metallum-iris-opt] argument snapshot diagnostics failed", failure);
        }
    }

    public static void bindBuffer(final Object pass, final String name, final GpuBufferSlice slice) {
        State state = state(pass);
        if (state == null || slice == null) return;
        Slot slot = state.buffers.get(name);
        if (slot == null) return;
        MemorySegment handle = nativeHandle(slice.buffer());
        long before = state.current().generation();
        state.current().bindBuffer(slot.index, handle, slice.offset());
        if (state.current().generation() != before) UPDATES.increment();
    }

    public static void bindStorageBuffer(final Object pass, final int binding, final GpuBufferSlice slice) {
        State state = state(pass);
        if (state == null || slice == null) return;
        Slot slot = state.bufferIndices.get(binding);
        if (slot == null) return;
        long before = state.current().generation();
        state.current().bindBuffer(slot.index, nativeHandle(slice.buffer()), slice.offset());
        if (state.current().generation() != before) UPDATES.increment();
    }

    public static void bindTexture(
            final Object pass,
            final String name,
            final GpuTextureView view,
            final GpuSampler sampler
    ) {
        State state = state(pass);
        if (state == null) return;
        Slot texture = state.textures.get(name);
        if (texture != null) {
            long before = state.current().generation();
            state.current().bindTexture(texture.index, nativeHandle(view));
            if (state.current().generation() != before) UPDATES.increment();
        }
        Slot samplerSlot = state.samplers.get(name);
        if (samplerSlot != null) {
            long before = state.current().generation();
            state.current().bindSampler(samplerSlot.index, nativeHandle(sampler));
            if (state.current().generation() != before) UPDATES.increment();
        }
    }

    public static void bindStorageImage(final Object pass, final String name, final GpuTextureView view) {
        State state = state(pass);
        if (state == null) return;
        Slot slot = state.textures.get(name);
        if (slot == null) return;
        long before = state.current().generation();
        state.current().bindTexture(slot.index, nativeHandle(view));
        if (state.current().generation() != before) UPDATES.increment();
    }

    public static void markEncoded(final Object pass) {
        State state = state(pass);
        if (state != null && state.current().dirty()) {
            recordPatch(state.current());
            state.current().markEncoded();
            ENCODED.increment();
        }
    }

    /**
     * Returns the one dirty-entry batch used only by snapshot diagnostics.
     * Native execution deliberately remains owned by the render-state packet
     * and, on Metal 4 main rendering, the native MTL4ArgumentTable bridge.
     */
    public static IrisMetalArgumentTablePatch snapshotPatch(final Object pass) {
        if (!enabled()) {
            PATCH_REJECTIONS.increment();
            return IrisMetalArgumentTablePatch.rejected("diagnostics-disabled");
        }
        State state = state(pass);
        if (state == null) {
            PATCH_REJECTIONS.increment();
            return IrisMetalArgumentTablePatch.rejected("pipeline-layout-unavailable");
        }
        IrisMetalArgumentTablePatch patch;
        try {
            patch = IrisMetalArgumentTablePatch.from(state.current());
        } catch (RuntimeException failure) {
            PATCH_REJECTIONS.increment();
            return IrisMetalArgumentTablePatch.rejected("snapshot-invalid");
        }
        recordPatch(patch);
        return patch;
    }

    private static void recordPatch(final IrisMetalArgumentSnapshot snapshot) {
        try {
            recordPatch(IrisMetalArgumentTablePatch.from(snapshot));
        } catch (RuntimeException failure) {
            PATCH_REJECTIONS.increment();
        }
    }

    private static void recordPatch(final IrisMetalArgumentTablePatch patch) {
        PATCH_SNAPSHOTS.increment();
        PATCH_ENTRIES.add(patch.entries().size());
    }

    public static void advanceAfterSubmit() {
        if (!enabled()) return;
        synchronized (PASSES) {
            for (State state : PASSES.values()) state.ring.advanceAfterSubmit();
        }
    }

    public static Stats stats() {
        return new Stats(
                LAYOUTS.sum(),
                UPDATES.sum(),
                ENCODED.sum(),
                PATCH_SNAPSHOTS.sum(),
                PATCH_ENTRIES.sum(),
                PATCH_REJECTIONS.sum()
        );
    }

    public static synchronized void resetStats() {
        LAYOUTS.reset();
        UPDATES.reset();
        ENCODED.reset();
        PATCH_SNAPSHOTS.reset();
        PATCH_ENTRIES.reset();
        PATCH_REJECTIONS.reset();
    }

    public static boolean diagnosticsEnabled() {
        return SNAPSHOT_DIAGNOSTICS;
    }

    private static boolean enabled() {
        return SNAPSHOT_DIAGNOSTICS;
    }

    private static State state(final Object pass) {
        if (!enabled()) return null;
        synchronized (PASSES) {
            return PASSES.get(pass);
        }
    }

    private static IrisMetalOptimizationPlan.ArgumentLayout layout(final Object pipeline)
            throws ReflectiveOperationException {
        Object resourcesValue = invoke(pipeline, "resources");
        List<IrisMetalOptimizationPlan.ArgumentSlot> slots = new ArrayList<>();
        if (resourcesValue instanceof Collection<?> resources) {
            for (Object resource : resources) {
                String name = String.valueOf(invoke(resource, "name"));
                String kind = String.valueOf(invoke(resource, "kind"));
                int index = ((Number) invoke(resource, "bindingIndex")).intValue();
                switch (kind) {
                    case "UNIFORM_BUFFER" -> slots.add(new IrisMetalOptimizationPlan.ArgumentSlot(
                            name, IrisMetalOptimizationPlan.ArgumentSlot.Kind.BUFFER, index, false));
                    case "STORAGE_BUFFER" -> slots.add(new IrisMetalOptimizationPlan.ArgumentSlot(
                            name, IrisMetalOptimizationPlan.ArgumentSlot.Kind.BUFFER, index, true));
                    case "SAMPLED_IMAGE" -> {
                        slots.add(new IrisMetalOptimizationPlan.ArgumentSlot(
                                name, IrisMetalOptimizationPlan.ArgumentSlot.Kind.TEXTURE, index, false));
                        slots.add(new IrisMetalOptimizationPlan.ArgumentSlot(
                                name + "#sampler", IrisMetalOptimizationPlan.ArgumentSlot.Kind.SAMPLER, index, false));
                    }
                    case "STORAGE_IMAGE" -> slots.add(new IrisMetalOptimizationPlan.ArgumentSlot(
                            name, IrisMetalOptimizationPlan.ArgumentSlot.Kind.TEXTURE, index, true));
                    case "TEXEL_BUFFER" -> slots.add(new IrisMetalOptimizationPlan.ArgumentSlot(
                            name, IrisMetalOptimizationPlan.ArgumentSlot.Kind.TEXTURE, index, false));
                    default -> throw new IllegalStateException("Unknown Metal resource kind " + kind);
                }
            }
        }
        return IrisMetalOptimizationPlan.ArgumentLayout.of(slots);
    }

    private static MemorySegment nativeHandle(final Object resource) {
        if (resource == null) return MemorySegment.NULL;
        try {
            Object handle = invoke(resource, "nativeHandle");
            return handle instanceof MemorySegment segment ? segment : MemorySegment.NULL;
        } catch (ReflectiveOperationException ignored) {
            try {
                Object texture = invoke(resource, "texture");
                Object handle = invoke(texture, "nativeHandle");
                return handle instanceof MemorySegment segment ? segment : MemorySegment.NULL;
            } catch (ReflectiveOperationException nested) {
                return MemorySegment.NULL;
            }
        }
    }

    private static Object field(final Object target, final String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke(final Object target, final String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method.invoke(target);
                }
            }
            type = type.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    public record Stats(
            long layouts,
            long updates,
            long encodedSnapshots,
            long patchSnapshots,
            long patchEntries,
            long patchRejections
    ) {
    }

    private record Slot(int index) {
    }

    private static final class State {
        private final IrisMetalOptimizationPlan.ArgumentLayout layout;
        private final IrisMetalArgumentSnapshot.Ring ring;
        private final Map<String, Slot> buffers = new HashMap<>();
        private final Map<Integer, Slot> bufferIndices = new HashMap<>();
        private final Map<String, Slot> textures = new HashMap<>();
        private final Map<String, Slot> samplers = new HashMap<>();

        private State(final IrisMetalOptimizationPlan.ArgumentLayout layout) {
            this.layout = layout;
            this.ring = new IrisMetalArgumentSnapshot.Ring(layout, MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT);
            for (IrisMetalOptimizationPlan.ArgumentSlot slot : layout.slots()) {
                Slot value = new Slot(slot.index());
                switch (slot.kind()) {
                    case BUFFER -> {
                        buffers.put(slot.name(), value);
                        bufferIndices.put(slot.index(), value);
                    }
                    case TEXTURE -> textures.put(slot.name(), value);
                    case SAMPLER -> {
                        String name = slot.name().endsWith("#sampler")
                                ? slot.name().substring(0, slot.name().length() - "#sampler".length())
                                : slot.name();
                        samplers.put(name, value);
                    }
                }
            }
        }

        private IrisMetalArgumentSnapshot current() {
            return ring.current();
        }
    }
}
