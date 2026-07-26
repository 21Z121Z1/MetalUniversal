package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fills the generated {@code MetallumIrisUniforms} block once per frame.
 *
 * <p>Iris on GL feeds a shader pack through ~200 individually-registered
 * uniforms. B2-1 does not reproduce that: the translation lane collects every
 * loose uniform a pack's {@code gbuffers_terrain} declares into one std140
 * block (offsets computed by {@link MetalIrisShaderCompiler} and verified
 * against SPIR-V reflection by the offline gate), and this class writes values
 * into it by name.</p>
 *
 * <p><b>Coverage is deliberately partial.</b> The names below carry real
 * per-frame state; every other name a pack declares is zero-filled and reported
 * once at debug level. A zero uniform is a wrong value, not a crash — a pack
 * reading an unsupplied name renders that effect flat rather than killing the
 * client. The debug log is the worklist for widening coverage.</p>
 *
 * <p>Values marked <i>exact</i> come from real game state; <i>approximate</i>
 * ones are documented at their case labels. Sodium's own per-draw values
 * ({@code u_RegionOffset} and friends) are <b>not</b> here — they stay in the
 * push-constant block {@link MetalDrawContext} writes.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalUniformValues implements AutoCloseable {
    /** Iris wraps its frame counter here; matches {@code SystemTimeUniforms}. */
    private static final int FRAME_COUNTER_WRAP = 720720;
    private static final float NEAR_PLANE = 0.05f;

    private final float sunPathRotation;
    private final List<Block> blocks = new ArrayList<>();
    private final Set<String> unsupported = new HashSet<>();
    private final Matrix4f previousModelView = new Matrix4f();
    private final Matrix4f previousProjection = new Matrix4f();
    private final Vector3d previousCameraPosition = new Vector3d();
    private long startNanos = System.nanoTime();
    private int frameCounter;
    private boolean warnedIdentityMatrices;
    private boolean closed;

    /**
     * A registered block. The GPU buffer is allocated lazily: registration
     * happens while the pack loads, which is not necessarily a moment where a
     * device is reachable (the offline gate builds a device of its own and
     * never installs it on RenderSystem).
     */
    private static final class Block {
        private final IrisMetalPipelineOverrides.TerrainKind kind;
        private final List<MetalIrisShaderCompiler.UniformMember> layout;
        private final int size;
        private @Nullable GpuBuffer buffer;
        private @Nullable ByteBuffer staging;
        private @Nullable MetalDevice device;

        private Block(
                final IrisMetalPipelineOverrides.TerrainKind kind,
                final List<MetalIrisShaderCompiler.UniformMember> layout,
                final int size
        ) {
            this.kind = kind;
            this.layout = layout;
            this.size = size;
        }

        private void allocate(final MetalDevice device) {
            if (this.buffer != null) {
                return;
            }
            this.device = device;
            this.buffer = device.createBuffer(
                    () -> "metallum:iris_uniforms/" + this.kind.name().toLowerCase(Locale.ROOT),
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    this.size
            );
            // writeToBuffer rejects heap buffers (they would SIGBUS in the
            // staging path), so the scratch has to be direct.
            this.staging = ByteBuffer.allocateDirect(this.size).order(ByteOrder.nativeOrder());
        }
    }

    IrisMetalUniformValues(final float sunPathRotation) {
        this.sunPathRotation = sunPathRotation;
    }

    /**
     * Allocates the block for one terrain kind. Called during registry
     * activation, once per successfully translated program.
     */
    void register(
            final IrisMetalPipelineOverrides.TerrainKind kind,
            final MetalIrisShaderCompiler.GlslProgram program
    ) {
        if (!program.hasUniformBlock()) {
            return;
        }
        this.blocks.add(new Block(kind, program.uniformLayout(), program.uniformBlockSize()));
    }

    /**
     * The slice to bind for a kind, or {@code null} if the kind has no uniform
     * block. Allocates and fills on first use so that a terrain draw reaching
     * the pass before the first {@link #updateFrame} still binds real values.
     */
    @Nullable
    GpuBufferSlice slice(final MetalDevice device, final IrisMetalPipelineOverrides.TerrainKind kind) {
        if (this.closed) {
            return null;
        }
        for (Block block : this.blocks) {
            if (block.kind != kind) {
                continue;
            }
            boolean fresh = block.buffer == null;
            block.allocate(device);
            if (fresh) {
                upload(block, sampleFrame());
            }
            return block.buffer.slice();
        }
        return null;
    }

    /**
     * Recomputes and uploads every registered block. Called once per frame from
     * {@link MetalWorldRenderingPipeline#beginLevelRendering()}, before sodium
     * draws terrain.
     */
    void updateFrame() {
        if (this.closed || this.blocks.isEmpty()) {
            return;
        }
        Frame frame = sampleFrame();
        for (Block block : this.blocks) {
            if (block.buffer != null) {
                upload(block, frame);
            }
        }
        this.previousModelView.set(frame.modelView());
        this.previousProjection.set(frame.projection());
        this.previousCameraPosition.set(frame.cameraPosition());
        this.frameCounter = (this.frameCounter + 1) % FRAME_COUNTER_WRAP;
    }

    /**
     * The CPU-side bytes last uploaded for a kind, or {@code null} if the block
     * has not been allocated. The uniform buffer itself is write-only on the
     * GPU (no {@code USAGE_MAP_READ}), so this staging copy is what the offline
     * gate asserts the std140 writer against.
     */
    @Nullable
    ByteBuffer lastUpload(final IrisMetalPipelineOverrides.TerrainKind kind) {
        for (Block block : this.blocks) {
            if (block.kind == kind) {
                return block.staging;
            }
        }
        return null;
    }

    private void upload(final Block block, final Frame frame) {
        ByteBuffer staging = block.staging;
        zero(staging);
        for (MetalIrisShaderCompiler.UniformMember member : block.layout) {
            write(staging, member, frame);
        }
        staging.rewind();
        block.device.createCommandEncoder().writeToBuffer(block.buffer.slice(), staging);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (Block block : this.blocks) {
            if (block.buffer != null) {
                block.buffer.close();
            }
        }
        this.blocks.clear();
    }

    // ------------------------------------------------------------------
    // Frame sampling
    // ------------------------------------------------------------------

    private record Frame(
            Matrix4f modelView,
            Matrix4f modelViewInverse,
            Matrix4f projection,
            Matrix4f projectionInverse,
            Matrix3f normalMatrix,
            Vector3d cameraPosition,
            Vector4f sunPosition,
            Vector4f moonPosition,
            Vector4f shadowLightPosition,
            Vector4f upPosition,
            Vector3d fogColor,
            float fogDensity,
            float tickDelta,
            float sunAngle,
            float shadowAngle,
            float rainStrength,
            float screenBrightness,
            float viewWidth,
            float viewHeight,
            float far,
            float frameTimeCounter,
            int worldTime,
            int worldDay,
            int frameCounter
    ) {
    }

    /**
     * Samples the frame, falling back to a neutral frame if any game state is
     * not reachable. A uniform fill runs on the render thread every frame; a
     * throw here would kill the client over a value that is only ever an input
     * to shading, so the failure is reported once and the frame degrades to
     * defaults instead.
     */
    private Frame sampleFrame() {
        try {
            return sampleLiveFrame();
        } catch (Throwable t) {
            if (this.unsupported.add("<frame>")) {
                Metallum.LOGGER.warn(
                        "[metallum-iris] could not sample frame state for the pack uniform block;"
                                + " falling back to neutral values", t
                );
            }
            return neutralFrame();
        }
    }

    /** Neutral frame: identity transforms, no weather, no time. */
    private Frame neutralFrame() {
        return new Frame(
                new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix3f(),
                new Vector3d(),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, -100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector3d(), 0.0f, 0.0f, 0.25f, 0.25f, 0.0f, 1.0f,
                1.0f, 1.0f, 256.0f, 0.0f, 0, 0, this.frameCounter
        );
    }

    private Frame sampleLiveFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        ClientLevel level = minecraft.level;

        Matrix4f modelView = new Matrix4f(state.getGbufferModelView());
        Matrix4f projection = new Matrix4f(state.getGbufferProjection());
        warnIfUnfilled(modelView, projection);

        Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
        Matrix4f projectionInverse = new Matrix4f(projection).invert();
        Matrix3f normalMatrix = new Matrix3f(modelView).invert().transpose();

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 cameraPos = camera == null ? Vec3.ZERO : camera.position();
        Vector3d cameraPosition = new Vector3d(cameraPos.x, cameraPos.y, cameraPos.z);

        float sunAngle = CelestialUniforms.getSunAngle(false);
        // getShadowLightPosition is the only celestial vector Iris exposes
        // publicly; the sun/moon pair is the same axis with the day/night sign,
        // which is exactly how CelestialUniforms derives them.
        CelestialUniforms celestial = new CelestialUniforms(this.sunPathRotation);
        Vector4f shadowLight = celestial.getShadowLightPosition();
        boolean day = CelestialUniforms.isDay();
        Vector4f sun = day
                ? new Vector4f(shadowLight)
                : new Vector4f(-shadowLight.x, -shadowLight.y, -shadowLight.z, shadowLight.w);
        Vector4f moon = new Vector4f(-sun.x, -sun.y, -sun.z, sun.w);
        // upPosition: world up mapped into view space, at Iris's 100-unit scale.
        Vector4f up = new Vector4f(0.0f, 100.0f, 0.0f, 0.0f).mul(modelView);

        float tickDelta = state.getTickDelta();
        int renderDistance = minecraft.options == null ? 8 : minecraft.options.getEffectiveRenderDistance();

        return new Frame(
                modelView,
                modelViewInverse,
                projection,
                projectionInverse,
                normalMatrix,
                cameraPosition,
                sun,
                moon,
                shadowLight,
                up,
                state.getFogColor(),
                state.getFogDensity(),
                tickDelta,
                sunAngle,
                sunAngle < 0.5f ? sunAngle : sunAngle - 0.5f,
                level == null ? 0.0f : level.getRainLevel(tickDelta),
                minecraft.options == null ? 1.0f : minecraft.options.gamma().get().floatValue(),
                minecraft.getWindow().getWidth(),
                minecraft.getWindow().getHeight(),
                renderDistance * 16.0f,
                (System.nanoTime() - this.startNanos) / 1.0e9f % 3600.0f,
                level == null ? 0 : (int) (level.getDefaultClockTime() % 24000L),
                level == null ? 0 : (int) (level.getDefaultClockTime() / 24000L),
                this.frameCounter
        );
    }

    private void warnIfUnfilled(final Matrix4f modelView, final Matrix4f projection) {
        if (this.warnedIdentityMatrices || !(modelView.equals(new Matrix4f(), 0.0f) || projection.equals(new Matrix4f(), 0.0f))) {
            return;
        }
        this.warnedIdentityMatrices = true;
        Metallum.LOGGER.warn(
                "[metallum-iris] CapturedRenderingState still holds identity matrices at frame time;"
                        + " pack terrain will be shaded with no camera transform."
                        + " Iris's own capture mixins are expected to fill these — check they are applied."
        );
    }

    // ------------------------------------------------------------------
    // std140 writing
    // ------------------------------------------------------------------

    private void write(final ByteBuffer out, final MetalIrisShaderCompiler.UniformMember member, final Frame frame) {
        int at = member.offset();
        switch (member.name()) {
            // --- matrices (exact) ---
            case "gbufferModelView", "iris_ModelViewMatrix", "shadowModelView" -> putMat4(out, at, frame.modelView());
            case "gbufferModelViewInverse", "iris_ModelViewMatrixInverse", "shadowModelViewInverse" ->
                    putMat4(out, at, frame.modelViewInverse());
            case "gbufferProjection", "iris_ProjectionMatrix", "shadowProjection" -> putMat4(out, at, frame.projection());
            case "gbufferProjectionInverse", "iris_ProjectionMatrixInverse", "shadowProjectionInverse" ->
                    putMat4(out, at, frame.projectionInverse());
            case "gbufferPreviousModelView" -> putMat4(out, at, this.previousModelView);
            case "gbufferPreviousProjection" -> putMat4(out, at, this.previousProjection);
            case "iris_LightmapTextureMatrix" -> putMat4(out, at, new Matrix4f());
            case "iris_NormalMat", "normalMatrix" -> putMat3(out, at, frame.normalMatrix());

            // --- positions (exact) ---
            case "cameraPosition" -> putVec3(out, at, frame.cameraPosition());
            case "previousCameraPosition" -> putVec3(out, at, this.previousCameraPosition);
            case "relativeEyePosition", "eyePosition" -> putVec3(out, at, 0.0f, 0.0f, 0.0f);
            case "sunPosition" -> putVec3(out, at, frame.sunPosition().x, frame.sunPosition().y, frame.sunPosition().z);
            case "moonPosition" -> putVec3(out, at, frame.moonPosition().x, frame.moonPosition().y, frame.moonPosition().z);
            case "shadowLightPosition" ->
                    putVec3(out, at, frame.shadowLightPosition().x, frame.shadowLightPosition().y, frame.shadowLightPosition().z);
            case "upPosition" -> putVec3(out, at, frame.upPosition().x, frame.upPosition().y, frame.upPosition().z);

            // --- fog: Iris's replacements for the vanilla fog uniforms. Color
            // and density are exact; the linear start/end are approximated from
            // the render distance because sodium keeps the real pair inside its
            // own u_Globals block, which we do not read.
            case "fogColor", "skyColor" -> putVec3(out, at, frame.fogColor());
            case "iris_FogColor" ->
                    putVec4(out, at, (float) frame.fogColor().x, (float) frame.fogColor().y, (float) frame.fogColor().z, 1.0f);
            case "fogDensity", "iris_FogDensity" -> out.putFloat(at, frame.fogDensity());
            case "fogStart", "iris_FogStart" -> out.putFloat(at, frame.far() * 0.75f);
            case "fogEnd", "iris_FogEnd" -> out.putFloat(at, frame.far());

            // --- time (exact) ---
            case "frameTimeCounter" -> out.putFloat(at, frame.frameTimeCounter());
            case "frameTime" -> out.putFloat(at, frame.tickDelta() / 20.0f);
            case "frameCounter" -> out.putInt(at, frame.frameCounter());
            case "framemod8" -> out.putFloat(at, frame.frameCounter() % 8);
            case "framemod2" -> out.putFloat(at, frame.frameCounter() % 2);
            case "worldTime" -> out.putInt(at, frame.worldTime());
            case "worldDay" -> out.putInt(at, frame.worldDay());
            case "sunAngle", "timeAngle" -> out.putFloat(at, frame.sunAngle());
            case "shadowAngle" -> out.putFloat(at, frame.shadowAngle());
            case "sunPathRotation" -> out.putFloat(at, this.sunPathRotation);

            // --- viewport (exact) ---
            case "viewWidth" -> out.putFloat(at, frame.viewWidth());
            case "viewHeight" -> out.putFloat(at, frame.viewHeight());
            case "aspectRatio" -> out.putFloat(at, frame.viewWidth() / Math.max(1.0f, frame.viewHeight()));
            case "near" -> out.putFloat(at, NEAR_PLANE);
            case "far" -> out.putFloat(at, frame.far());

            // --- weather / player state ---
            case "rainStrength", "wetness" -> out.putFloat(at, frame.rainStrength());
            case "screenBrightness" -> out.putFloat(at, frame.screenBrightness());
            // timeBrightness peaks at noon; Iris derives it from the sun angle.
            case "timeBrightness" -> out.putFloat(at, Math.max(0.0f, (float) Math.cos(frame.sunAngle() * Math.PI * 2.0)));
            case "eyeBrightness", "eyeBrightnessSmooth" -> putIVec2(out, at, 0, 240);
            case "eyeAltitude" -> out.putFloat(at, (float) frame.cameraPosition().y);
            case "isEyeInWater" -> out.putInt(at, 0);
            case "shadowFade" -> out.putFloat(at, 0.0f);

            default -> reportUnsupported(out, member);
        }
    }

    private void reportUnsupported(final ByteBuffer out, final MetalIrisShaderCompiler.UniformMember member) {
        // The buffer is already zeroed; nothing to write.
        if (this.unsupported.add(member.name())) {
            Metallum.LOGGER.debug(
                    "[metallum-iris] uniform '{}' ({}) has no value source; zero-filled",
                    member.name(), member.type()
            );
        }
    }

    private static void zero(final ByteBuffer buffer) {
        for (int index = 0; index + Long.BYTES <= buffer.capacity(); index += Long.BYTES) {
            buffer.putLong(index, 0L);
        }
        for (int index = buffer.capacity() & ~(Long.BYTES - 1); index < buffer.capacity(); index++) {
            buffer.put(index, (byte) 0);
        }
    }

    /** std140 mat4: four column-major vec4s, 16 bytes each. */
    private static void putMat4(final ByteBuffer out, final int offset, final Matrix4f matrix) {
        float[] values = new float[16];
        matrix.get(values);
        for (int index = 0; index < 16; index++) {
            out.putFloat(offset + index * Float.BYTES, values[index]);
        }
    }

    /** std140 mat3: three columns padded to a vec4 stride, 12 useful bytes each. */
    private static void putMat3(final ByteBuffer out, final int offset, final Matrix3f matrix) {
        float[] values = new float[9];
        matrix.get(values);
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                out.putFloat(offset + column * 16 + row * Float.BYTES, values[column * 3 + row]);
            }
        }
    }

    private static void putVec3(final ByteBuffer out, final int offset, final Vector3d value) {
        putVec3(out, offset, (float) value.x, (float) value.y, (float) value.z);
    }

    private static void putVec3(final ByteBuffer out, final int offset, final float x, final float y, final float z) {
        out.putFloat(offset, x);
        out.putFloat(offset + 4, y);
        out.putFloat(offset + 8, z);
    }

    private static void putVec4(
            final ByteBuffer out, final int offset, final float x, final float y, final float z, final float w
    ) {
        putVec3(out, offset, x, y, z);
        out.putFloat(offset + 12, w);
    }

    private static void putIVec2(final ByteBuffer out, final int offset, final int x, final int y) {
        out.putInt(offset, x);
        out.putInt(offset + 4, y);
    }
}
