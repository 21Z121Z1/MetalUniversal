package com.metallum.client.validation;

import com.metallum.Metallum;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reflection-only seam for optional Sodium validation helpers.
 *
 * <p>The production client entrypoint is always listed in fabric.mod.json, so
 * it must remain linkable when Sodium is not installed. Keep every Sodium type
 * name as data in this class and fail closed to vanilla-safe validation
 * behavior when the optional API is absent or changes.</p>
 */
final class SodiumValidationBridge {
    private static final String RENDERER =
            "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";
    private static final String FLAWLESS_FRAMES =
            "net.caffeinemc.mods.sodium.client.util.FlawlessFrames";
    private static final @Nullable RendererApi RENDERER_API = discoverRendererApi();
    private static final AtomicBoolean RENDERER_FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean FLAWLESS_FAILURE_LOGGED = new AtomicBoolean();

    private SodiumValidationBridge() {
    }

    /** Frames between a scripted block mutation and its first terrain draw. */
    static int terrainMutationLeadFrames() {
        if (RENDERER_API != null) {
            return 0; // The supported Sodium important-rebuild path runs before drawing.
        }
        if (isSodiumClassPresent()) {
            throw new IllegalStateException("Unsupported Sodium validation API; terrain publication timing is unknown");
        }
        // Vanilla 26.3 compiles/uploads sections after executing the frame graph.
        return 1;
    }

    static boolean terrainSettled() {
        RendererApi api = RENDERER_API;
        if (api == null) {
            return true;
        }
        try {
            Object renderer = api.instanceNullable().invoke(null);
            return renderer == null || Boolean.TRUE.equals(api.terrainRenderComplete().invoke(renderer));
        } catch (ReflectiveOperationException | LinkageError exception) {
            logRendererFailure(exception);
            return true;
        }
    }

    static void requestImportantRebuild(
            final int minX,
            final int minY,
            final int minZ,
            final int maxX,
            final int maxY,
            final int maxZ
    ) {
        RendererApi api = RENDERER_API;
        if (api == null) {
            return;
        }
        try {
            Object renderer = api.instanceNullable().invoke(null);
            if (renderer != null) {
                api.scheduleRebuildForBlockArea().invoke(
                        renderer,
                        minX, minY, minZ,
                        maxX, maxY, maxZ,
                        true
                );
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            logRendererFailure(exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean enableFlawlessFrames(final String owner) {
        try {
            Class<?> flawlessFrames = Class.forName(
                    FLAWLESS_FRAMES,
                    false,
                    SodiumValidationBridge.class.getClassLoader()
            );
            Object provider = flawlessFrames.getMethod("getProvider").invoke(null);
            if (!(provider instanceof Function function)) {
                return false;
            }
            Object sink = function.apply(owner);
            if (!(sink instanceof Consumer consumer)) {
                return false;
            }
            consumer.accept(Boolean.TRUE);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (FLAWLESS_FAILURE_LOGGED.compareAndSet(false, true)
                    && isSodiumClassPresent()) {
                Metallum.LOGGER.warn(
                        "Sodium FlawlessFrames validation bridge unavailable; continuing without it",
                        exception
                );
            }
            return false;
        }
    }

    private static @Nullable RendererApi discoverRendererApi() {
        try {
            Class<?> renderer = Class.forName(
                    RENDERER,
                    false,
                    SodiumValidationBridge.class.getClassLoader()
            );
            return new RendererApi(
                    renderer.getMethod("instanceNullable"),
                    renderer.getMethod("isTerrainRenderComplete"),
                    renderer.getMethod(
                            "scheduleRebuildForBlockArea",
                            int.class, int.class, int.class,
                            int.class, int.class, int.class,
                            boolean.class
                    )
            );
        } catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
    }

    private static boolean isSodiumClassPresent() {
        try {
            Class.forName(RENDERER, false, SodiumValidationBridge.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }

    private static void logRendererFailure(final Throwable throwable) {
        if (RENDERER_FAILURE_LOGGED.compareAndSet(false, true)) {
            Metallum.LOGGER.warn(
                    "Sodium renderer validation bridge failed; treating terrain as settled",
                    throwable
            );
        }
    }

    private record RendererApi(
            Method instanceNullable,
            Method terrainRenderComplete,
            Method scheduleRebuildForBlockArea
    ) {
    }
}
