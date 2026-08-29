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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Shared Java ownership layer for Metal 3 argument buffers and Metal 4 argument
 * tables. Existing native setters remain the execution mechanism; this class
 * freezes the ABI, owns one snapshot per in-flight slot and records only actual
 * logical mutations.
 *
 * <p>The snapshots are owned by the compiled pipeline/layout generation rather
 * than by a short-lived {@code MetalRenderPass}. Minecraft creates render-pass
 * objects as command-encoding transactions, while an argument table can be
 * reused by later encoders. Each pass therefore maps to its pipeline-owned
 * state; the three in-flight slots survive pass destruction and rotate exactly
 * once after submit.</p>
 */
public final class IrisMetalArgumentBindingRuntime {
    private static final Object STATE_LOCK = new Object();
    /** Weak pass aliases into the pipeline-owned state. */
    private static final Map<Object, State> PASSES = new WeakHashMap<>();
    /** Weak generation owners; State deliberately does not retain the pipeline key. */
    private static final Map<Object, State> PIPELINES = new WeakHashMap<>();
    private static final LongAdder LAYOUTS = new LongAdder();
    private static final LongAdder UPDATES = new LongAdder();
    private static final LongAdder ENCODED = new LongAdder();

    private IrisMetalArgumentBindingRuntime() {
    }

    public static void attachPipeline(final Object pass) {
        if (!enabled() || pass == null) return;
        try {
            Object pipeline = field(pass, "compiledPipeline");
            if (pipeline == null) return;
            IrisMetalOptimizationPlan.ArgumentLayout layout = layout(pipeline);
            synchronized (STATE_LOCK) {
                State state = PIPELINES.get(pipeline);
                if (state == null || state.layout.stableHash() != layout.stableHash()) {
                    state = new State(layout);
                    PIPELINES.put(pipeline, state);
                    LAYOUTS.increment();
                }
                // A render pass is only a transient alias. Re-attaching the same
                // pipeline never allocates another in-flight ring.
                PASSES.put(pass, state);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Metallum.LOGGER.warn("[metallum-iris-opt] argument layout attachment failed", failure);
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
            state.current().markEncoded();
            ENCODED.increment();
        }
    }

    public static void advanceAfterSubmit() {
        if (!enabled()) return;
        synchronized (STATE_LOCK) {
            // Advance each pipeline-owned ring once. Iterating PASSES would rotate
            // one ring repeatedly when multiple render passes used the same PSO.
            for (State state : new HashSet<>(PIPELINES.values())) {
                state.ring.advanceAfterSubmit();
            }
        }
    }

    public static Stats stats() {
        return new Stats(LAYOUTS.sum(), UPDATES.sum(), ENCODED.sum());
    }

    public static synchronized void resetStats() {
        LAYOUTS.reset();
        UPDATES.reset();
        ENCODED.reset();
    }

    private static boolean enabled() {
        return IrisMetalOptimizationPlan.ENABLE_ARGUMENT_TABLES
                || IrisMetalAdvancedOptimizationConfig.ARGUMENT_TABLES;
    }

    private static State state(final Object pass) {
        if (!enabled()) return null;
        synchronized (STATE_LOCK) {
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

    public record Stats(long layouts, long updates, long encodedSnapshots) {
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
