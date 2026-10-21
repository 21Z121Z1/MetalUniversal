package com.metallum.client.metal.render;

/** Validated CPU transport defaults; explicit false properties retain the legacy routes. */
public final class MetalBackendOptimizations {
    public static final boolean FUSED_STATE_DRAW = enabled("fusedStateDraw");
    public static final boolean REUSE_ENCODER_STATE = enabled("reuseEncoderState");
    public static final boolean REUSE_NATIVE_ENCODER_ARGUMENTS = enabled("reuseNativeEncoderArguments");
    public static final boolean DYNAMIC_UPLOAD_RANGE_COPY = enabled("dynamicUploadRangeCopy");
    public static final boolean BATCH_INDEXED_VERTEX_REBASE = enabled("batchIndexedVertexRebase");
    public static final boolean NATIVE_INDEXED_VERTEX_REBASE = enabled("nativeIndexedVertexRebase");

    private MetalBackendOptimizations() { }

    private static boolean enabled(String name) {
        return Boolean.parseBoolean(System.getProperty("metallum.opt." + name, "true"));
    }

    public static Snapshot snapshot() {
        return new Snapshot(FUSED_STATE_DRAW, REUSE_ENCODER_STATE,
                REUSE_NATIVE_ENCODER_ARGUMENTS, DYNAMIC_UPLOAD_RANGE_COPY, BATCH_INDEXED_VERTEX_REBASE,
                NATIVE_INDEXED_VERTEX_REBASE);
    }

    public record Snapshot(boolean fusedStateDraw, boolean reuseEncoderState,
                           boolean reuseNativeEncoderArguments, boolean dynamicUploadRangeCopy,
                           boolean batchIndexedVertexRebase, boolean nativeIndexedVertexRebase) { }
}
