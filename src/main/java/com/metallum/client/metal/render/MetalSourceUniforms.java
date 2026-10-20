package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Immutable bytes of actual Minecraft 26.3 source bindings, owned by the draw-history transaction. */
final class MetalSourceUniforms {
    static final int DYNAMIC_BYTES = 160;
    static final int GLOBAL_BYTES = 48;
    static final int FOG_BYTES = 40;
    private final byte[] dynamic;
    private final byte @Nullable [] globals;
    private final byte @Nullable [] fog;

    private MetalSourceUniforms(final byte[] dynamic, final byte @Nullable [] globals,final byte @Nullable [] fog) {
        this.dynamic = dynamic.clone();
        this.globals = globals == null ? null : globals.clone();
        this.fog = fog == null ? null : fog.clone();
    }

    static @Nullable MetalSourceUniforms capture(final @Nullable GpuBufferSlice dynamic,
                                                 final @Nullable GpuBufferSlice globals) {
        return capture(dynamic,globals,null);
    }

    static @Nullable MetalSourceUniforms capture(final @Nullable GpuBufferSlice dynamic,
            final @Nullable GpuBufferSlice globals,final @Nullable GpuBufferSlice fog) {
        byte[] d = read(dynamic,DYNAMIC_BYTES), g = globals == null ? null : read(globals,GLOBAL_BYTES);
        byte[] f=fog == null ? null : read(fog,FOG_BYTES);
        if (d == null || (globals != null && g == null) || (fog != null && f == null)) return null;
        return decode(d,g,f);
    }

    static @Nullable MetalSourceUniforms decode(final byte[] dynamic, final byte @Nullable [] globals) {
        return decode(dynamic,globals,null);
    }

    static @Nullable MetalSourceUniforms decode(final byte[] dynamic,final byte @Nullable [] globals,final byte @Nullable [] fog) {
        if (dynamic.length != DYNAMIC_BYTES || (globals != null && globals.length != GLOBAL_BYTES)
                || (fog != null && fog.length != FOG_BYTES)) return null;
        ByteBuffer d = bytes(dynamic);
        // DynamicTransforms: two mat4, ColorModulator vec4, ModelOffset vec3; ignore std140 padding.
        for (int offset=0;offset<156;offset+=4) if (!Float.isFinite(d.getFloat(offset))) return null;
        if (globals != null) {
            ByteBuffer g = bytes(globals);
            // CameraBlockPos, MenuBlurRadius and UseRgss are integers, not float fields.
            for (int offset : new int[]{12,16,20,24,28,32,36}) if (!Float.isFinite(g.getFloat(offset))) return null;
        }
        if(fog!=null) {
            ByteBuffer f=bytes(fog);
            for(int offset=0;offset<FOG_BYTES;offset+=4)if(!Float.isFinite(f.getFloat(offset)))return null;
        }
        return new MetalSourceUniforms(dynamic,globals,fog);
    }

    private static byte @Nullable [] read(final @Nullable GpuBufferSlice slice, final int size) {
        return slice != null && slice.buffer() instanceof MetalGpuBuffer buffer ? buffer.copySourceUniformBytes(slice,size) : null;
    }

    private static ByteBuffer bytes(final byte[] value) { return ByteBuffer.wrap(value).order(ByteOrder.nativeOrder()); }

    private Matrix4f matrix(final int offset) {
        // JOML's Unsafe ByteBuffer path requires native memory. These are owned heap bytes.
        ByteBuffer b=bytes(dynamic);float[] values=new float[16];
        for(int i=0;i<16;i++)values[i]=b.getFloat(offset+i*4);
        return new Matrix4f().set(values);
    }
    Matrix4f modelView() { return matrix(0); }
    Matrix4f textureMatrix() { return matrix(64); }
    Vector4f colorModulator() {
        ByteBuffer b=bytes(dynamic);return new Vector4f(b.getFloat(128),b.getFloat(132),b.getFloat(136),b.getFloat(140));
    }
    Vector3f modelOffset() {
        ByteBuffer b=bytes(dynamic);return new Vector3f(b.getFloat(144),b.getFloat(148),b.getFloat(152));
    }
    boolean hasGlobals() { return globals != null; }
    boolean hasFog() { return fog != null; }
    /** Compare shader inputs, excluding only the unused std140 ModelOffset padding. */
    boolean sameHistoryInputs(final MetalSourceUniforms other) {
        return java.util.Arrays.equals(dynamic, 0, 156, other.dynamic, 0, 156)
                && java.util.Arrays.equals(globals, other.globals) && java.util.Arrays.equals(fog, other.fog);
    }
    float glintAlpha() {
        if (globals == null) throw new IllegalStateException("Source Globals were not bound");
        return bytes(globals).getFloat(12);
    }
    float gameTime() {
        if (globals == null) throw new IllegalStateException("Source Globals were not bound");
        return bytes(globals).getFloat(28);
    }
    void writeDynamic(final ByteBuffer destination) { destination.put(dynamic); }
    void writeGlobals(final ByteBuffer destination) {
        if (globals == null) throw new IllegalStateException("Source Globals were not bound");
        destination.put(globals);
    }
    void writeFog(final ByteBuffer destination) {
        if(fog==null)throw new IllegalStateException("Source Fog was not bound");
        destination.put(fog);
    }
}
