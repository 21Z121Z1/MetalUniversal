package com.metallum.mixin.terrain;

import com.metallum.client.terrain.TerrainSchedulingController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Experimental tuning surface for the Apple-Silicon terrain hill climb.
 *
 * <p>Every hook returns Sodium/MetalUniversal's existing constant when the
 * corresponding property is absent or invalid, so merely carrying this mixin
 * does not alter the shipping scheduling policy. The benchmark workflow uses
 * the properties to explore nearby policy points without duplicating the
 * controller or changing its queue ownership semantics.</p>
 */
@Mixin(value = TerrainSchedulingController.class, remap = false)
public abstract class TerrainSchedulingTuningMixin {
    private static final String WARMUP_PROPERTY =
            "metallum.opt.terrainAdaptiveSchedulingWarmupFrames";
    private static final String BUILD_RATIO_PROPERTY =
            "metallum.opt.terrainAdaptiveSchedulingBuildBudgetRatio";
    private static final String UPLOAD_RATIO_PROPERTY =
            "metallum.opt.terrainAdaptiveSchedulingUploadBudgetRatio";

    private static final int MAX_WARMUP_FRAMES = 600;
    private static final double MIN_BUDGET_RATIO = 0.01;
    private static final double MAX_BUDGET_RATIO = 0.50;

    @ModifyConstant(
            method = "createRuntime",
            constant = @Constant(intValue = 30),
            require = 1
    )
    private static int metallum$tuneWarmupFrames(final int original) {
        final String raw = System.getProperty(WARMUP_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return original;
        }
        try {
            final int value = Integer.parseInt(raw.trim());
            return value >= 0 && value <= MAX_WARMUP_FRAMES ? value : original;
        } catch (NumberFormatException ignored) {
            return original;
        }
    }

    @ModifyConstant(
            method = "computeDecision",
            constant = @Constant(doubleValue = 0.10),
            require = 1
    )
    private double metallum$tuneBuildBudgetRatio(final double original) {
        return metallum$budgetRatio(BUILD_RATIO_PROPERTY, original);
    }

    @ModifyConstant(
            method = "computeDecision",
            constant = @Constant(doubleValue = 0.08),
            require = 1
    )
    private double metallum$tuneUploadBudgetRatio(final double original) {
        return metallum$budgetRatio(UPLOAD_RATIO_PROPERTY, original);
    }

    private static double metallum$budgetRatio(final String property, final double original) {
        final String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return original;
        }
        try {
            final double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value)
                    && value >= MIN_BUDGET_RATIO
                    && value <= MAX_BUDGET_RATIO
                    ? value
                    : original;
        } catch (NumberFormatException ignored) {
            return original;
        }
    }
}
