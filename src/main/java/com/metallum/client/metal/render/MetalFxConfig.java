package com.metallum.client.metal.render;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/** Stable JVM-property configuration for the optional MetalFX path. */
@Environment(EnvType.CLIENT)
final class MetalFxConfig {
    static final String MODE_PROPERTY = "metallum.metalfx.mode";
    static final String SCALE_PROPERTY = "metallum.metalfx.scale";
    static final String REACTIVE_MASK_PROPERTY = "metallum.metalfx.reactiveMask";
    static final String FRAME_GENERATION_PROPERTY = "metallum.metalfx.frameGeneration";
    static final String FRAME_GENERATION_OUTPUT_WIDTH_PROPERTY =
            "metallum.metalfx.frameGenerationOutputWidth";

    private static final String CONFIG_FILE = "metallum-metalfx.properties";
    private static final String MODE_KEY = "mode";
    private static final String SCALE_KEY = "scalePercent";
    private static final String REACTIVE_MASK_KEY = "transparencyReactiveMask";
    private static final String FRAME_GENERATION_KEY = "frameGeneration";
    private static final Object PERSISTENCE_LOCK = new Object();
    private static volatile PersistentSettings persistentSettings;

    enum Mode {
        OFF,
        SPATIAL,
        TEMPORAL,
        AUTO
    }

    enum Scale {
        HALF("50%", 50, 0.5F),
        QUALITY("67%", 67, 0.67F),
        NATIVE("100%", 100, 1.0F);

        final String label;
        final int percent;
        final float ratio;

        Scale(final String label, final int percent, final float ratio) {
            this.label = label;
            this.percent = percent;
            this.ratio = ratio;
        }

        static Scale fromRatio(final float ratio) {
            if (Math.abs(ratio - 1.0F) < 0.01F) return NATIVE;
            if (Math.abs(ratio - 0.67F) < 0.02F) return QUALITY;
            return HALF;
        }

        static Scale fromPercent(final int percent) {
            if (percent >= 84) return NATIVE;
            if (percent >= 59) return QUALITY;
            return HALF;
        }
    }

    final Mode requestedMode;
    final float scale;
    final boolean debug;
    final boolean transparencyReactiveMask;
    final boolean frameGeneration;
    final int frameGenerationOutputWidth;
    // Reactive-policy tuning (launch-argument knobs, not persisted). See
    // docs/cutout-shimmer-remediation-2026-07-27.md; 1.0 across the board
    // restores the pre-remediation full-suppression policy.
    final float cutoutReactiveEdgeWeight;
    final float cutoutReactiveInteriorWeight;
    final float depthEdgeReactiveCap;
    final float transparencyReactiveValue;
    final boolean skyFarPlaneMotion;
    final float disocclusionReactiveCap;
    final boolean mergeDepthDilation;

    private MetalFxConfig(
            final Mode requestedMode,
            final float scale,
            final boolean debug,
            final boolean transparencyReactiveMask,
            final boolean frameGeneration,
            final int frameGenerationOutputWidth,
            final float cutoutReactiveEdgeWeight,
            final float cutoutReactiveInteriorWeight,
            final float depthEdgeReactiveCap,
            final float transparencyReactiveValue,
            final boolean skyFarPlaneMotion,
            final float disocclusionReactiveCap,
            final boolean mergeDepthDilation
    ) {
        this.requestedMode = requestedMode;
        this.scale = scale;
        this.debug = debug;
        this.transparencyReactiveMask = transparencyReactiveMask;
        this.frameGeneration = frameGeneration;
        this.frameGenerationOutputWidth = frameGenerationOutputWidth;
        this.cutoutReactiveEdgeWeight = cutoutReactiveEdgeWeight;
        this.cutoutReactiveInteriorWeight = cutoutReactiveInteriorWeight;
        this.depthEdgeReactiveCap = depthEdgeReactiveCap;
        this.transparencyReactiveValue = transparencyReactiveValue;
        this.skyFarPlaneMotion = skyFarPlaneMotion;
        this.disocclusionReactiveCap = disocclusionReactiveCap;
        this.mergeDepthDilation = mergeDepthDilation;
    }

    static MetalFxConfig load() {
        PersistentSettings defaults = persistentSettings();
        Mode mode = parseMode(System.getProperty(MODE_PROPERTY), defaults.mode);
        float scale = parseScale(System.getProperty(SCALE_PROPERTY), defaults.scalePercent / 100.0F);
        boolean debug = parseBoolean(System.getProperty("metallum.metalfx.debug"), false);
        boolean transparencyReactiveMask = parseBoolean(
                System.getProperty(REACTIVE_MASK_PROPERTY), defaults.transparencyReactiveMask
        );
        boolean frameGeneration = parseBoolean(
                System.getProperty(FRAME_GENERATION_PROPERTY), defaults.frameGeneration
        );
        int frameGenerationOutputWidth = parseBoundedInt(
                System.getProperty(FRAME_GENERATION_OUTPUT_WIDTH_PROPERTY), 1440, 640, 3840
        );
        float cutoutReactiveEdgeWeight = parseUnitFloat(
                System.getProperty("metallum.metalfx.cutoutReactiveEdgeWeight"), 0.35F
        );
        float cutoutReactiveInteriorWeight = parseUnitFloat(
                System.getProperty("metallum.metalfx.cutoutReactiveInteriorWeight"), 0.0F
        );
        float depthEdgeReactiveCap = parseUnitFloat(
                System.getProperty("metallum.metalfx.depthEdgeReactiveCap"), 0.5F
        );
        float transparencyReactiveValue = parseUnitFloat(
                System.getProperty("metallum.metalfx.transparencyReactiveValue"), 0.9F
        );
        boolean skyFarPlaneMotion = parseBoolean(
                System.getProperty("metallum.metalfx.skyFarPlaneMotion"), true
        );
        float disocclusionReactiveCap = parseUnitFloat(
                System.getProperty("metallum.metalfx.disocclusionReactiveCap"), 0.85F
        );
        boolean mergeDepthDilation = parseBoolean(
                System.getProperty("metallum.metalfx.mergeDepthDilation"), true
        );
        return new MetalFxConfig(
                mode, scale, debug, transparencyReactiveMask, frameGeneration,
                frameGenerationOutputWidth,
                cutoutReactiveEdgeWeight, cutoutReactiveInteriorWeight,
                depthEdgeReactiveCap, transparencyReactiveValue,
                skyFarPlaneMotion, disocclusionReactiveCap, mergeDepthDilation
        );
    }

    static Mode configuredModeForSodium() {
        return parseMode(System.getProperty(MODE_PROPERTY), persistentSettings().mode);
    }

    static Scale configuredScaleForSodium() {
        PersistentSettings defaults = persistentSettings();
        String override = System.getProperty(SCALE_PROPERTY);
        return override == null
                ? Scale.fromPercent(defaults.scalePercent)
                : Scale.fromRatio(parseScale(override, defaults.scalePercent / 100.0F));
    }

    static boolean configuredTransparencyReactiveMaskForSodium() {
        return parseBoolean(
                System.getProperty(REACTIVE_MASK_PROPERTY), persistentSettings().transparencyReactiveMask
        );
    }

    static boolean configuredFrameGenerationForSodium() {
        return parseBoolean(
                System.getProperty(FRAME_GENERATION_PROPERTY), persistentSettings().frameGeneration
        );
    }

    static boolean hasSystemPropertyOverride(final String property) {
        return System.getProperty(property) != null;
    }

    static void setModeFromSodium(final Mode mode) {
        updatePersistent(settings -> new PersistentSettings(
                mode == null ? settings.mode : mode,
                settings.scalePercent,
                settings.transparencyReactiveMask,
                settings.frameGeneration
        ));
    }

    static void setScaleFromSodium(final Scale scale) {
        updatePersistent(settings -> new PersistentSettings(
                settings.mode,
                scale == null ? settings.scalePercent : scale.percent,
                settings.transparencyReactiveMask,
                settings.frameGeneration
        ));
    }

    static void setTransparencyReactiveMaskFromSodium(final Boolean enabled) {
        updatePersistent(settings -> new PersistentSettings(
                settings.mode,
                settings.scalePercent,
                enabled == null ? settings.transparencyReactiveMask : enabled,
                settings.frameGeneration
        ));
    }

    static void setFrameGenerationFromSodium(final Boolean enabled) {
        updatePersistent(settings -> new PersistentSettings(
                settings.mode,
                settings.scalePercent,
                settings.transparencyReactiveMask,
                enabled == null ? settings.frameGeneration : enabled
        ));
    }

    static void flushPersistent() {
        synchronized (PERSISTENCE_LOCK) {
            writePersistentSettings(persistentSettings());
        }
    }

    static int phaseCount(final float scale) {
        if (!(scale > 0.0F) || !Float.isFinite(scale)) {
            return 1;
        }
        // The configured scale is the render/display ratio. MetalFX's phase
        // guidance is expressed as the inverse upscale factor (1.5x -> 18,
        // 2x -> 32), so convert before applying the documented formula.
        float upscaleFactor = 1.0F / scale;
        return Math.max(1, (int) Math.ceil(8.0F * upscaleFactor * upscaleFactor));
    }

    static int scaledDimension(final int displayDimension, final float scale) {
        if (displayDimension <= 0) {
            return 1;
        }
        if (scale >= 0.999F) {
            return displayDimension;
        }
        int scaled = Math.max(1, Math.round(displayDimension * scale));
        if (scaled > 1) {
            scaled &= ~1;
        }
        return Math.max(1, scaled);
    }

    static float frameGenerationOutputScale(final int displayWidth, final int maximumOutputWidth) {
        if (displayWidth <= 0 || maximumOutputWidth <= 0 || displayWidth <= maximumOutputWidth) {
            return 1.0F;
        }
        return maximumOutputWidth / (float) displayWidth;
    }

    static float textureLodBias(final int renderWidth, final int displayWidth) {
        if (renderWidth <= 0 || displayWidth <= 0 || renderWidth >= displayWidth) {
            return 0.0F;
        }
        float scale = renderWidth / (float) displayWidth;
        return (float) (Math.log(scale) / Math.log(2.0)) - 1.0F;
    }

    private static int parseBoundedInt(
            final String value,
            final int fallback,
            final int minimum,
            final int maximum
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static Mode parseMode(final String value, final Mode fallback) {
        if (value == null) return fallback;
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    static boolean parseBoolean(final String value, final boolean fallback) {
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value.trim())) return true;
        if ("false".equalsIgnoreCase(value.trim())) return false;
        return fallback;
    }

    static float parseUnitFloat(final String value, final float fallback) {
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isFinite(parsed)) {
                return Math.clamp(parsed, 0.0F, 1.0F);
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    static float parseScale(final String value, final float fallback) {
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Math.abs(parsed - 1.0F) < 0.01F) return 1.0F;
            if (Math.abs(parsed - 0.67F) < 0.02F) return 0.67F;
            if (Math.abs(parsed - 0.5F) < 0.01F) return 0.5F;
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static PersistentSettings persistentSettings() {
        PersistentSettings cached = persistentSettings;
        if (cached != null) return cached;
        synchronized (PERSISTENCE_LOCK) {
            if (persistentSettings == null) {
                persistentSettings = readPersistentSettings();
            }
            return persistentSettings;
        }
    }

    private static void updatePersistent(final java.util.function.UnaryOperator<PersistentSettings> update) {
        synchronized (PERSISTENCE_LOCK) {
            PersistentSettings current = persistentSettings();
            PersistentSettings next = update.apply(current);
            persistentSettings = next;
            writePersistentSettings(next);
        }
    }

    private static PersistentSettings readPersistentSettings() {
        Properties properties = new Properties();
        Path path = configPath();
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            } catch (IOException ignored) {
                // An unreadable optional file must never prevent the client from starting.
            }
        }
        Mode mode = parseMode(properties.getProperty(MODE_KEY), Mode.OFF);
        int scalePercent;
        try {
            scalePercent = Integer.parseInt(properties.getProperty(SCALE_KEY, "67").trim());
        } catch (NumberFormatException ignored) {
            scalePercent = 67;
        }
        scalePercent = Scale.fromPercent(scalePercent).percent;
        boolean transparencyReactiveMask = parseBoolean(
                properties.getProperty(REACTIVE_MASK_KEY), true
        );
        boolean frameGeneration = parseBoolean(properties.getProperty(FRAME_GENERATION_KEY), false);
        return new PersistentSettings(mode, scalePercent, transparencyReactiveMask, frameGeneration);
    }

    private static void writePersistentSettings(final PersistentSettings settings) {
        Properties properties = new Properties();
        properties.setProperty(MODE_KEY, settings.mode.name());
        properties.setProperty(SCALE_KEY, Integer.toString(settings.scalePercent));
        properties.setProperty(REACTIVE_MASK_KEY, Boolean.toString(settings.transparencyReactiveMask));
        properties.setProperty(FRAME_GENERATION_KEY, Boolean.toString(settings.frameGeneration));

        Path path = configPath();
        Path parent = path.getParent();
        if (parent == null) return;
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "MetalFX settings");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private static Path configPath() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve(CONFIG_FILE);
        } catch (Throwable ignored) {
            return Path.of(System.getProperty("user.dir", ".")).resolve(CONFIG_FILE);
        }
    }

    private record PersistentSettings(
            Mode mode,
            int scalePercent,
            boolean transparencyReactiveMask,
            boolean frameGeneration
    ) {
    }
}
