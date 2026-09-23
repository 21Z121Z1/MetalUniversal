package com.metallum.client.metal.render;

/** Pure policy for Minecraft's existing limiter; never sleeps or changes simulation. */
public final class FramePacingPolicy {
    private FramePacingPolicy() { }

    public record Config(boolean enabled, int foregroundFps, int backgroundFps,
                         int minimizedFps, int idleFps, long idleAfterMillis, int batteryFps) {
        public Config(boolean enabled, int foregroundFps, int backgroundFps,
                      int minimizedFps, int idleFps, long idleAfterMillis) {
            this(enabled, foregroundFps, backgroundFps, minimizedFps, idleFps, idleAfterMillis, 0);
        }
        public Config {
            for (int fps : new int[]{foregroundFps, backgroundFps, minimizedFps, idleFps, batteryFps}) {
                // 260 is Minecraft's uncapped sentinel, not an executable cap.
                if (fps < 0 || fps >= 260) throw new IllegalArgumentException("Pacing FPS must be 0 (inherit) or 1..259");
            }
            if (idleAfterMillis <= 0) throw new IllegalArgumentException("Idle threshold must be positive");
        }
    }

    public record Decision(int requestedFps, int effectiveFps, String owner, String state) {
        public long targetIntervalNanos() {
            return owner.equals("metallum") && effectiveFps > 0 && effectiveFps < 260
                    ? Math.round(1_000_000_000.0 / effectiveFps) : -1L;
        }
    }

    public static Decision decide(Config config, int vanillaLimit, boolean focused, boolean minimized,
                                  long idleMillis, boolean externalLimiter) {
        return decide(config, vanillaLimit, focused, minimized, idleMillis, externalLimiter, -1);
    }

    public static Decision decide(Config config, int vanillaLimit, boolean focused, boolean minimized,
                                  long idleMillis, boolean externalLimiter, int batteryState) {
        if (!config.enabled()) return new Decision(0, vanillaLimit, "minecraft", "disabled");
        if (externalLimiter) return new Decision(0, vanillaLimit, "dynamic-fps", "delegated");
        int requested = config.foregroundFps();
        String state = "foreground";
        if (minimized) {
            state = "minimized";
            requested = lower(requested, config.minimizedFps());
        } else if (!focused) {
            state = "background";
            requested = lower(requested, config.backgroundFps());
        } else if (idleMillis >= config.idleAfterMillis()) {
            state = "idle";
            requested = lower(requested, config.idleFps());
        }
        if (batteryState == 1 && config.batteryFps() > 0) {
            requested = lower(requested, config.batteryFps());
            state += "-battery";
        }
        int effective = requested > 0 ? Math.min(vanillaLimit, requested) : vanillaLimit;
        return new Decision(requested, effective, requested > 0 ? "metallum" : "minecraft", state);
    }

    private static int lower(int a, int b) { return a == 0 ? b : b == 0 ? a : Math.min(a, b); }
}
