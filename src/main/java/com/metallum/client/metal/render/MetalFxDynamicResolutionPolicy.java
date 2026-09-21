package com.metallum.client.metal.render;

/**
 * Hysteretic controller for the optional MetalFX temporal input scale.
 *
 * <p>It is deliberately a policy object rather than a direct target mutator.
 * A caller must apply a changed scale at a frame boundary and recreate the
 * complete motion/depth/reactive resource set in one transaction. Until that
 * caller is wired, the controller still provides deterministic admission and
 * telemetry while the shipped default remains unchanged.</p>
 */
public final class MetalFxDynamicResolutionPolicy {
    public static final String ENABLE_PROPERTY = "metallum.metalfx.dynamicResolution";
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(ENABLE_PROPERTY, "false")
    );
    public static final float MIN_SCALE = 0.50F;
    public static final float MAX_SCALE = 1.00F;
    public static final float STEP = 0.10F;
    public static final int DEFAULT_DOWN_HYSTERESIS = 3;
    public static final int DEFAULT_UP_HYSTERESIS = 8;
    public static final long DEFAULT_TARGET_FRAME_NANOS = 16_666_667L;

    private MetalFxDynamicResolutionPolicy() {
    }

    public static Controller create(final float initialScale) {
        return new Controller(initialScale, DEFAULT_TARGET_FRAME_NANOS, ENABLED);
    }

    public static Controller create(
            final float initialScale,
            final long targetFrameNanos,
            final boolean enabled
    ) {
        return new Controller(initialScale, targetFrameNanos, enabled);
    }

    public static final class Controller {
        private final long targetFrameNanos;
        private final boolean enabled;
        private final int downHysteresis;
        private final int upHysteresis;
        private float scale;
        private int overBudgetFrames;
        private int underBudgetFrames;

        public Controller(final float initialScale, final long targetFrameNanos, final boolean enabled) {
            if (!Float.isFinite(initialScale)) {
                throw new IllegalArgumentException("initialScale must be finite");
            }
            if (targetFrameNanos <= 0L) {
                throw new IllegalArgumentException("targetFrameNanos must be positive");
            }
            this.scale = clampScale(initialScale);
            this.targetFrameNanos = targetFrameNanos;
            this.enabled = enabled;
            this.downHysteresis = DEFAULT_DOWN_HYSTERESIS;
            this.upHysteresis = DEFAULT_UP_HYSTERESIS;
        }

        /** Updates the policy with one completed GPU frame. */
        public Decision update(final long gpuFrameNanos) {
            if (!enabled) {
                resetCounters();
                return decision(false, false, "feature-disabled");
            }
            if (gpuFrameNanos <= 0L) {
                resetCounters();
                return decision(false, false, "gpu-time-unavailable");
            }

            // Leave 5% headroom before declaring a frame over budget. This
            // prevents timer quantization from toggling the scale at the exact
            // refresh deadline.
            long overBudgetThreshold = Math.addExact(
                    targetFrameNanos,
                    Math.max(1L, targetFrameNanos / 20L)
            );
            long underBudgetThreshold = Math.max(1L, targetFrameNanos * 85L / 100L);
            if (gpuFrameNanos > overBudgetThreshold) {
                overBudgetFrames = Math.min(downHysteresis, overBudgetFrames + 1);
                underBudgetFrames = 0;
                if (overBudgetFrames >= downHysteresis && scale > MIN_SCALE) {
                    float previous = scale;
                    scale = clampScale(scale - STEP);
                    resetCounters();
                    return decision(true, scale != previous, "over-budget-decrease");
                }
                return decision(false, false, "over-budget-hysteresis");
            }
            if (gpuFrameNanos < underBudgetThreshold) {
                underBudgetFrames = Math.min(upHysteresis, underBudgetFrames + 1);
                overBudgetFrames = 0;
                if (underBudgetFrames >= upHysteresis && scale < MAX_SCALE) {
                    float previous = scale;
                    scale = clampScale(scale + STEP);
                    resetCounters();
                    return decision(true, scale != previous, "under-budget-increase");
                }
                return decision(false, false, "under-budget-hysteresis");
            }

            resetCounters();
            return decision(false, false, "in-budget");
        }

        public float scale() {
            return scale;
        }

        public long targetFrameNanos() {
            return targetFrameNanos;
        }

        public boolean enabled() {
            return enabled;
        }

        public int overBudgetFrames() {
            return overBudgetFrames;
        }

        public int underBudgetFrames() {
            return underBudgetFrames;
        }

        private Decision decision(final boolean changed, final boolean scaleChanged, final String reason) {
            return new Decision(
                    enabled,
                    scale,
                    targetFrameNanos,
                    changed && scaleChanged,
                    reason,
                    overBudgetFrames,
                    underBudgetFrames
            );
        }

        private void resetCounters() {
            overBudgetFrames = 0;
            underBudgetFrames = 0;
        }
    }

    public record Decision(
            boolean enabled,
            float scale,
            long targetFrameNanos,
            boolean changed,
            String reason,
            int overBudgetFrames,
            int underBudgetFrames
    ) {
        public Decision {
            if (!Float.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE) {
                throw new IllegalArgumentException("dynamic resolution scale is out of range");
            }
            if (targetFrameNanos <= 0L || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("dynamic resolution decision is incomplete");
            }
            if (overBudgetFrames < 0 || underBudgetFrames < 0) {
                throw new IllegalArgumentException("hysteresis counters must be non-negative");
            }
        }
    }

    private static float clampScale(final float value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }
}
