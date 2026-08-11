package com.metallum.client.metal.render;

/**
 * Runtime switches for optimizations whose correctness still needs physical
 * GPU equivalence evidence. The conservative default is deliberately false;
 * each lane can be re-enabled independently with its documented property.
 */
public final class MetalOptimizationProperties {
    public static final String MIPMAP_CACHE = "metallum.opt.mipmapCache";
    public static final String TEXTURE_COPY_DEDUP = "metallum.opt.textureCopyDedup";
    public static final String UPLOAD_DEDUP = "metallum.opt.uploadDedup";
    public static final String BUFFER_UPLOAD_DEDUP = "metallum.opt.bufferUploadDedup";

    private MetalOptimizationProperties() {
    }

    public static boolean enabled(final String property, final boolean defaultValue) {
        return Boolean.parseBoolean(
                System.getProperty(property, Boolean.toString(defaultValue))
        );
    }
}
