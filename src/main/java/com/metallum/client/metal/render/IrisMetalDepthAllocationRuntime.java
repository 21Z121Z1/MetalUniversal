package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Releases generation-owned depthtex1/depthtex2 allocations when the complete
 * optimization plan proves they are dead, while retaining a fail-closed lazy
 * recreation path for an unexpected runtime request.
 */
public final class IrisMetalDepthAllocationRuntime {
    private static final int DEPTH_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;
    private static final Set<Object> TARGETS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final LongAdder pruneAttempts = new LongAdder();
    private static final LongAdder prunedPairs = new LongAdder();
    private static final LongAdder recreatedResources = new LongAdder();
    private static final LongAdder pruneFailures = new LongAdder();
    private static final LongAdder captureSkips = new LongAdder();

    private IrisMetalDepthAllocationRuntime() {
    }

    public static synchronized void register(final Object targets) {
        if (targets == null) return;
        TARGETS.add(targets);
        prune(targets);
    }

    public static synchronized void onPlanAvailable() {
        for (Object targets : Set.copyOf(TARGETS)) prune(targets);
    }

    public static Object ensureDepthtex1(final Object targets) {
        return ensure(targets, 1, false);
    }

    public static Object ensureDepthtex1View(final Object targets) {
        return ensure(targets, 1, true);
    }

    public static Object ensureDepthtex2(final Object targets) {
        return ensure(targets, 2, false);
    }

    public static Object ensureDepthtex2View(final Object targets) {
        return ensure(targets, 2, true);
    }

    public static void ensureCaptureDestination(final Object targets, final int index) {
        ensure(targets, index, false);
        ensure(targets, index, true);
    }

    public static void recordCaptureSkipped(final int index) {
        if (enabled() && (index == 1 || index == 2)) {
            captureSkips.increment();
        }
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(
                pruneAttempts.sum(),
                prunedPairs.sum(),
                recreatedResources.sum(),
                pruneFailures.sum(),
                captureSkips.sum()
        );
    }

    public static synchronized void reset() {
        pruneAttempts.reset();
        prunedPairs.reset();
        recreatedResources.reset();
        pruneFailures.reset();
        captureSkips.reset();
    }

    private static boolean enabled() {
        return IrisMetalOptimizationPlan.ENABLE_RESOURCE_PRUNING
                || IrisMetalAdvancedOptimizationConfig.DEPTH_LIVENESS;
    }

    private static void prune(final Object targets) {
        if (!enabled() || IrisMetalExperimentalOptimizer.active() == null) return;
        pruneAttempts.increment();
        try {
            if (!IrisMetalOptimizationBootstrap.depthtex1Required()) closePair(targets, 1);
            if (!IrisMetalOptimizationBootstrap.depthtex2Required()) closePair(targets, 2);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            pruneFailures.increment();
            Metallum.LOGGER.warn(
                    "[metallum-iris-opt] depth allocation pruning failed; keeping conservative allocation",
                    failure
            );
        }
    }

    private static Object ensure(final Object targets, final int index, final boolean view) {
        try {
            String textureField = index == 1 ? "noTranslucentsDepth" : "noHandDepth";
            String viewField = index == 1 ? "noTranslucentsDepthView" : "noHandDepthView";
            Field texture = field(targets.getClass(), textureField);
            Field textureView = field(targets.getClass(), viewField);
            Object currentTexture = texture.get(targets);
            Object currentView = textureView.get(targets);
            if (!closed(currentTexture) && !closed(currentView)) {
                return view ? currentView : currentTexture;
            }

            Object device = read(targets, "device");
            int width = ((Number) read(targets, "width")).intValue();
            int height = ((Number) read(targets, "height")).intValue();
            Object replacementTexture = createTexture(
                    device,
                    index == 1 ? "iris-depthtex1" : "iris-depthtex2",
                    width,
                    height
            );
            if (replacementTexture instanceof MetalGpuTexture metalTexture) {
                metalTexture.registerValidationIdentity();
            }
            Object replacementView = createView(replacementTexture);
            texture.set(targets, replacementTexture);
            textureView.set(targets, replacementView);
            recreatedResources.increment();

            String key = "depthtex" + index;
            if (WARNED.add(key)) {
                Metallum.LOGGER.warn(
                        "[metallum-iris-opt] {} was pruned but requested at runtime; recreated fail-closed. "
                                + "The generation liveness scan is incomplete for this shader pack.",
                        key
                );
            }
            return view ? replacementView : replacementTexture;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not recreate depthtex" + index, failure);
        }
    }

    private static void closePair(final Object targets, final int index) throws ReflectiveOperationException {
        String textureField = index == 1 ? "noTranslucentsDepth" : "noHandDepth";
        String viewField = index == 1 ? "noTranslucentsDepthView" : "noHandDepthView";
        Object view = read(targets, viewField);
        Object texture = read(targets, textureField);
        boolean wasLive = (view != null && !closed(view)) || (texture != null && !closed(texture));
        close(view);
        close(texture);
        if (wasLive) {
            prunedPairs.increment();
        }
    }

    private static Object createTexture(
            final Object device,
            final String label,
            final int width,
            final int height
    ) throws ReflectiveOperationException {
        Method selected = null;
        for (Method method : device.getClass().getMethods()) {
            if (method.getName().equals("createTexture") && method.getParameterCount() == 7) {
                selected = method;
                break;
            }
        }
        if (selected == null) {
            for (Method method : device.getClass().getDeclaredMethods()) {
                if (method.getName().equals("createTexture") && method.getParameterCount() == 7) {
                    selected = method;
                    break;
                }
            }
        }
        if (selected == null) throw new NoSuchMethodException("MetalDevice.createTexture/7");
        selected.setAccessible(true);
        return selected.invoke(device, label, DEPTH_USAGE, GpuFormat.D32_FLOAT, width, height, 1, 1);
    }

    private static Object createView(final Object texture) throws ReflectiveOperationException {
        Class<?> viewClass = Class.forName("com.metallum.client.metal.render.MetalGpuTextureView");
        for (Constructor<?> constructor : viewClass.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 3 && parameters[0].isAssignableFrom(texture.getClass())) {
                constructor.setAccessible(true);
                return constructor.newInstance(texture, 0, 1);
            }
        }
        throw new NoSuchMethodException("MetalGpuTextureView(texture, baseMip, count)");
    }

    private static boolean closed(final Object resource) throws ReflectiveOperationException {
        if (resource == null) return true;
        Method method = resource.getClass().getMethod("isClosed");
        return (boolean) method.invoke(resource);
    }

    private static void close(final Object resource) throws ReflectiveOperationException {
        if (resource == null || closed(resource)) return;
        Method method = resource.getClass().getMethod("close");
        method.invoke(resource);
    }

    private static Object read(final Object target, final String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).get(target);
    }

    private static Field field(Class<?> type, final String name) throws NoSuchFieldException {
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    public record Snapshot(
            long pruneAttempts,
            long prunedPairs,
            long recreatedResources,
            long pruneFailures,
            long captureSkips
    ) {
    }
}
