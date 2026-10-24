package com.metallum.client;

public final class ClientPerformanceOptions {
    public static final String MODEL_PART_INDEXED_LOOP_PROPERTY = "metallum.opt.modelPartIndexedLoop";
    public static final String LOADING_FPS_PROPERTY = "metallum.opt.loadingFps";

    private ClientPerformanceOptions() {
    }

    public static boolean modelPartIndexedLoopEnabled() {
        return Boolean.getBoolean(MODEL_PART_INDEXED_LOOP_PROPERTY);
    }

    public static int loadingFps() {
        String raw = System.getProperty(LOADING_FPS_PROPERTY);
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Math.max(0, Math.min(240, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
