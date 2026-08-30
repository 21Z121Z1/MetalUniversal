package com.metallum.mixin.terrain;

import com.metallum.client.terrain.TerrainSchedulingController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Experimental tuning surface for the Apple-Silicon terrain hill climb.
 *
 * <p>Properties are parsed once when the transformed controller class is
 * initialized. When a property is absent or invalid, the injected hook returns
 * Sodium/MetalUniversal's existing constant. This keeps repeated scheduler
 * decisions free of property lookup/parsing overhead while still letting each
 * benchmark JVM select one policy point at process start.</p>
 */
@Mixin(value = TerrainSchedulingController.class, remap = false)
public abstract class TerrainSchedulingTuningMixin {
    private static final String WARMUP_PROPERTY =
            "metallum.opt.terrainAdaptiveSchedulingWarmupFrames";
    private static final String BUILD_RATIO_PROPERTY =
            "metallum.opt.terrainAdaptiveSchedulingBuildBudgetRatio";
    private static final String UPLOAD_RATIO_PROPERTY =
            "metallum.opt.terrainAdaptiveSchedulingUploadBudgetRatio";
    private static final String CONSTRAINED_MULTIPLIER_PROPERTY =
            "metallum.opt.terrainAdaptiveSchedulingConstrainedMultiplier";
    private static final String SEVERE_MULTIPLIER_PROPERTY =
            "metallum.opt.terrainAdaptiveSchedulingSevereMultiplier";

    private static final int MAX_WARMUP_FRAMES = 600;
    private static final double MIN_BUDGET_RATIO = 0.01;
    private static final double MAX_BUDGET_RATIO = 0.50;
    private static final double MIN_PRESSURE_MULTIPLIER = 0.10;
    private static final double MAX_PRESSURE_MULTIPLIER = 1.00;

    private static final int WARMUP_OVERRIDE = metallum$intProperty(
            WARMUP_PROPERTY,
            0,
            MAX_WARMUP_FRAMES
    );
    private static final double BUILD_RATIO_OVERRIDE = metallum$doubleProperty(
            BUILD_RATIO_PROPERTY,
            MIN_BUDGET_RATIO,
            MAX_BUDGET_RATIO
    );
    private static final double UPLOAD_RATIO_OVERRIDE = metallum$doubleProperty(
            UPLOAD_RATIO_PROPERTY,
            MIN_BUDGET_RATIO,
            MAX_BUDGET_RATIO
    );
    private static final double CONSTRAINED_MULTIPLIER_OVERRIDE = metallum$doubleProperty(
            CONSTRAINED_MULTIPLIER_PROPERTY,
            MIN_PRESSURE_MULTIPLIER,
            MAX_PRESSURE_MULTIPLIER
    );
    private static final double SEVERE_MULTIPLIER_OVERRIDE = metallum$doubleProperty(
            SEVERE_MULTIPLIER_PROPERTY,
            MIN_PRESSURE_MULTIPLIER,
            MAX_PRESSURE_MULTIPLIER
    );

    @ModifyConstant(
            method = "createRuntime",
            constant = @Constant(intValue = 30),
            require = 1
    )
    private static int metallum$tuneWarmupFrames(final int original) {
        return WARMUP_OVERRIDE >= 0 ? WARMUP_OVERRIDE : original;
    }

    @ModifyConstant(
            method = "computeDecision",
            constant = @Constant(doubleValue = 0.10),
            require = 1
    )
    private double metallum$tuneBuildBudgetRatio(final double original) {
        return BUILD_RATIO_OVERRIDE >= 0.0 ? BUILD_RATIO_OVERRIDE : original;
    }

    @ModifyConstant(
            method = "computeDecision",
            constant = @Constant(doubleValue = 0.08),
            require = 1
    )
    private double metallum$tuneUploadBudgetRatio(final double original) {
        return UPLOAD_RATIO_OVERRIDE >= 0.0 ? UPLOAD_RATIO_OVERRIDE : original;
    }

    @ModifyConstant(
            method = "computeDecision",
            constant = @Constant(doubleValue = 0.75),
            require = 1
    )
    private double metallum$tuneConstrainedPressureMultiplier(final double original) {
        return CONSTRAINED_MULTIPLIER_OVERRIDE >= 0.0
                ? CONSTRAINED_MULTIPLIER_OVERRIDE
                : original;
    }

    @ModifyConstant(
            method = "computeDecision",
            constant = @Constant(doubleValue = 0.50),
            require = 1
    )
    private double metallum$tuneSeverePressureMultiplier(final double original) {
        return SEVERE_MULTIPLIER_OVERRIDE >= 0.0 ? SEVERE_MULTIPLIER_OVERRIDE : original;
    }

    private static int metallum$intProperty(
            final String property,
            final int minimum,
            final int maximum
    ) {
        final String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            final int value = Integer.parseInt(raw.trim());
            return value >= minimum && value <= maximum ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static double metallum$doubleProperty(
            final String property,
            final double minimum,
            final double maximum
    ) {
        final String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return -1.0;
        }
        try {
            final double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) && value >= minimum && value <= maximum ? value : -1.0;
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }
}
