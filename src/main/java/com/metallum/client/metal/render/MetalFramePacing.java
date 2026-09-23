package com.metallum.client.metal.render;

import com.google.gson.JsonObject;

/** Last limiter decision and render-thread restoration, shared with terrain/evidence. */
public final class MetalFramePacing {
    private static final FramePacingPolicy.Config CONFIG = new FramePacingPolicy.Config(
            Boolean.getBoolean("metallum.pacing.enabled"),
            Integer.getInteger("metallum.pacing.targetFps", 0),
            Integer.getInteger("metallum.pacing.backgroundFps", 0),
            Integer.getInteger("metallum.pacing.minimizedFps", 0),
            Integer.getInteger("metallum.pacing.idleFps", 0),
            Math.multiplyExact(Long.getLong("metallum.pacing.idleAfterSeconds", 60L), 1000L),
            Integer.getInteger("metallum.pacing.batteryFps", 0));
    private static volatile FramePacingPolicy.Decision latest = new FramePacingPolicy.Decision(0, 260, "minecraft", "unobserved");
    private static boolean windowObserved;
    private static boolean wasVisible;
    private static int lastWidth;
    private static int lastHeight;
    private static long lastPowerPoll;
    private static int batteryState = -1;

    private MetalFramePacing() { }

    public static int limit(int vanillaLimit, boolean focused, boolean minimized, long idleMillis, boolean externalLimiter) {
        if (CONFIG.enabled() && !externalLimiter && CONFIG.batteryFps() > 0) {
            long now = System.nanoTime();
            if (lastPowerPoll == 0L || now - lastPowerPoll >= 2_000_000_000L) {
                int state = org.lwjgl.sdl.SDLPower.SDL_GetPowerInfo(null, null);
                batteryState = state == org.lwjgl.sdl.SDLPower.SDL_POWERSTATE_ON_BATTERY ? 1
                        : state == org.lwjgl.sdl.SDLPower.SDL_POWERSTATE_UNKNOWN || state == org.lwjgl.sdl.SDLPower.SDL_POWERSTATE_ERROR ? -1 : 0;
                lastPowerPoll = now;
            }
        }
        latest = FramePacingPolicy.decide(CONFIG, vanillaLimit, focused, minimized, idleMillis, externalLimiter, batteryState);
        return latest.effectiveFps();
    }

    public static long targetIntervalNanos() { return latest.targetIntervalNanos(); }

    /** Called on the existing render thread before any temporal source work. */
    public static void observeWindow(boolean focused, boolean minimized, int width, int height) {
        boolean visible = focused && !minimized && width > 0 && height > 0;
        if (windowObserved && (visible != wasVisible || width != lastWidth || height != lastHeight)) {
            MetalFxManager.surfaceDiscontinuity("window visibility or size changed");
            FrameEvidenceRuntime.surfaceChanged();
        }
        MetalFxManager.observeWindowVisibility(visible);
        windowObserved = true;
        wasVisible = visible;
        lastWidth = width;
        lastHeight = height;
    }

    public static JsonObject snapshot() {
        FramePacingPolicy.Decision decision = latest;
        JsonObject result = new JsonObject();
        result.addProperty("enabled", CONFIG.enabled());
        result.addProperty("requestedFps", decision.requestedFps());
        result.addProperty("effectiveFps", decision.effectiveFps());
        result.addProperty("owner", decision.owner());
        result.addProperty("state", decision.state());
        result.addProperty("powerSource", CONFIG.batteryFps() == 0 ? "not-requested" : batteryState < 0 ? "unavailable" : batteryState == 1 ? "battery" : "external-or-no-battery");
        result.addProperty("actuator", "minecraft-FramerateLimiter.limitDisplayFPS");
        return result;
    }
}
