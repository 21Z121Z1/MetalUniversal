package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import kroppeb.stareval.function.FunctionReturn;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.IntSupplier;

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
 * <p>The production constructor consumes Iris's own {@link CustomUniforms}
 * graph. It contains both the official fixed inputs and the pack's
 * {@code variable.*}/{@code uniform.*} expressions, so values such as
 * {@code daytime}, {@code taaOffset} and {@code lightDirView} use the same
 * suppliers and evaluation order as Iris. The switch below is only for values
 * Iris marks externally managed by the active Mojang/Sodium draw.</p>
 *
 * <p>Values marked <i>exact</i> come from real game state; <i>approximate</i>
 * ones are documented at their case labels. Sodium's own per-draw values
 * ({@code u_RegionOffset} and friends) are <b>not</b> here — they stay in the
 * push-constant block {@link MetalDrawContext} writes.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalUniformValues implements AutoCloseable {
    private static final float NEAR_PLANE = 0.05f;
    private static final Matrix4fc LIGHTMAP_TEXTURE_MATRIX = new Matrix4f(
            1.0f / 256.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f / 256.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f / 256.0f, 0.0f,
            1.0f / 32.0f, 1.0f / 32.0f, 1.0f / 32.0f, 1.0f
    );
    private static final String CORE_MODEL_VIEW_INVERSE = "iris_ModelViewMatInverse";
    private static final String CORE_PROJECTION_INVERSE = "iris_ProjMatInverse";
    private static final String CORE_NORMAL_MATRIX = "iris_NormalMat";

    private final float sunPathRotation;
    private final @Nullable CustomUniforms customUniforms;
    private final @Nullable FrameUpdateNotifier updateNotifier;
    private final IntSupplier renderStageSource;
    private final boolean strict;
    private final List<Block> blocks = new ArrayList<>();
    private final Set<String> unsupported = new HashSet<>();
    private final Matrix4f previousModelView = new Matrix4f();
    private final Matrix4f previousProjection = new Matrix4f();
    private final Vector3d previousCameraPosition = new Vector3d();
    private boolean warnedIdentityMatrices;
    private boolean closed;

    /** Values whose Iris suppliers are evaluated at one core draw boundary. */
    record DrawUniformContext(
            @Nullable GpuTextureView gtexture,
            int atlasWidth,
            int atlasHeight,
            Optional<BlendFunction> blendFunction
    ) {
        private static final DrawUniformContext EMPTY = new DrawUniformContext(
                null, 0, 0, Optional.empty()
        );

        DrawUniformContext {
            Objects.requireNonNull(blendFunction, "blendFunction");
            if (atlasWidth < 0 || atlasHeight < 0) {
                throw new IllegalArgumentException("Iris atlas dimensions must be non-negative");
            }
        }

        static DrawUniformContext empty() {
            return EMPTY;
        }
    }

    /**
     * A registered block. The GPU buffer is allocated lazily: registration
     * happens while the pack loads, which is not necessarily a moment where a
     * device is reachable (the offline gate builds a device of its own and
     * never installs it on RenderSystem).
     */
    private static final class Block {
        private final Object token;
        private final String label;
        private final List<IrisMetalGlslLinker.UniformMember> layout;
        private final int size;
        private final OptionalDouble alphaTestReference;
        private @Nullable GpuBuffer buffer;
        private @Nullable ByteBuffer staging;
        private @Nullable MetalDevice device;

        private Block(
                final Object token,
                final String label,
                final List<IrisMetalGlslLinker.UniformMember> layout,
                final int size,
                final OptionalDouble alphaTestReference
        ) {
            this.token = token;
            this.label = label;
            this.layout = layout;
            this.size = size;
            this.alphaTestReference = alphaTestReference;
        }

        private void allocate(final MetalDevice device) {
            if (this.buffer != null) {
                return;
            }
            this.device = device;
            this.buffer = device.createBuffer(
                    () -> "metallum:iris_uniforms/" + this.label,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    this.size
            );
            // writeToBuffer rejects heap buffers (they would SIGBUS in the
            // staging path), so the scratch has to be direct.
            this.staging = ByteBuffer.allocateDirect(this.size).order(ByteOrder.nativeOrder());
        }
    }

    IrisMetalUniformValues(final float sunPathRotation) {
        this(sunPathRotation, null, null, () -> 0, false);
    }

    IrisMetalUniformValues(final float sunPathRotation, final IntSupplier renderStageSource) {
        this(sunPathRotation, null, null, renderStageSource, false);
    }

    IrisMetalUniformValues(
            final float sunPathRotation,
            final CustomUniforms customUniforms,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource
    ) {
        this(sunPathRotation, customUniforms, updateNotifier, renderStageSource, true);
    }

    private IrisMetalUniformValues(
            final float sunPathRotation,
            final @Nullable CustomUniforms customUniforms,
            final @Nullable FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource,
            final boolean strict
    ) {
        if ((customUniforms == null) != (updateNotifier == null)) {
            throw new IllegalArgumentException("Iris custom uniforms and frame notifier must be supplied together");
        }
        this.sunPathRotation = sunPathRotation;
        this.customUniforms = customUniforms;
        this.updateNotifier = updateNotifier;
        this.renderStageSource = Objects.requireNonNull(renderStageSource, "renderStageSource");
        this.strict = strict;
    }

    private static OptionalDouble alphaTestReference(final IrisMetalGlslLinker.LinkedRasterProgram program) {
        return OptionalDouble.of(program.program().alphaTest().reference());
    }

    /**
     * Allocates the block for one terrain kind. Called during registry
     * activation, once per successfully translated program.
     */
    void register(
            final ShaderKey kind,
            final IrisMetalGlslLinker.LinkedRasterProgram program
    ) {
        register(kind, kind.getName(), program);
    }

    void register(
            final Object token,
            final String label,
            final IrisMetalGlslLinker.LinkedRasterProgram program
    ) {
        if (program.uniformLayout().isEmpty()) {
            return;
        }
        for (Block block : this.blocks) {
            if (block.token.equals(token)) {
                if (block.size != program.uniformBlockSize()
                        || !block.layout.equals(program.uniformLayout())
                        || !block.alphaTestReference.equals(alphaTestReference(program))) {
                    throw new IllegalStateException(
                            "Iris uniform token was registered with two different layouts or alpha-test references: "
                                    + token
                    );
                }
                return;
            }
        }
        this.blocks.add(new Block(
                token,
                label,
                program.uniformLayout(),
                program.uniformBlockSize(),
                alphaTestReference(program)
        ));
    }

    /**
     * The slice to bind for a kind, or {@code null} if the kind has no uniform
     * block. Allocates and fills on first use so that a terrain draw reaching
     * the pass before the first {@link #updateFrame} still binds real values.
     */
    @Nullable
    GpuBufferSlice slice(final ShaderKey kind) {
        return slice((Object) kind);
    }

    @Nullable
    GpuBufferSlice slice(final Object token) {
        if (this.closed) {
            return null;
        }
        for (Block block : this.blocks) {
            if (block.token.equals(token) && block.buffer != null) {
                return block.buffer.slice();
            }
        }
        return null;
    }

    /**
     * Allocates and fills every registered block. Must run outside any encoder
     * at the generation frame boundary.
     */
    void prewarm(final MetalDevice device) {
        if (this.closed || this.blocks.isEmpty()) {
            return;
        }
        Frame frame = null;
        for (Block block : this.blocks) {
            if (block.buffer != null) {
                continue;
            }
            block.allocate(device);
            if (frame == null) {
                frame = sampleFrame();
            }
            upload(block, frame);
        }
    }

    /**
     * Recomputes and uploads every registered block. Called once per frame from
     * {@link MetalWorldRenderingPipeline#beginLevelRendering()}, before sodium
     * draws terrain.
     */
    void updateFrame() {
        if (this.closed) {
            return;
        }
        if (this.customUniforms != null) {
            try {
                Objects.requireNonNull(this.updateNotifier).onNewFrame();
                this.customUniforms.update();
            } catch (Throwable failure) {
                if (this.strict) {
                    throw new IllegalStateException("Iris uniform graph failed to update", failure);
                }
                if (this.unsupported.add("<custom-uniform-frame>")) {
                    Metallum.LOGGER.warn("[metallum-iris] Iris uniform graph failed to update", failure);
                }
            }
        }
        if (this.blocks.isEmpty()) {
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
    }

    /** Current Iris-compatible frame counter for diagnostics and pass tracing. */
    int frameCounter() {
        return SystemTimeUniforms.COUNTER.getAsInt();
    }

    /**
     * The CPU-side bytes last uploaded for a kind, or {@code null} if the block
     * has not been allocated. The uniform buffer itself is write-only on the
     * GPU (no {@code USAGE_MAP_READ}), so this staging copy is what the offline
     * gate asserts the std140 writer against.
     */
    @Nullable
    ByteBuffer lastUpload(final ShaderKey kind) {
        return lastUpload((Object) kind);
    }

    @Nullable
    ByteBuffer lastUpload(final Object token) {
        for (Block block : this.blocks) {
            if (block.token.equals(token)) {
                return block.staging;
            }
        }
        return null;
    }

    private void upload(final Block block, final Frame frame) {
        ByteBuffer staging = block.staging;
        zero(staging);
        for (IrisMetalGlslLinker.UniformMember member : block.layout) {
            if (usesMojangCoreTransforms(block.token) && isCoreDrawUniform(member.name())) {
                continue;
            }
            write(staging, member, frame, block.alphaTestReference);
        }
        staging.rewind();
        block.device.createCommandEncoder().writeToBuffer(block.buffer.slice(), staging);
    }

    int coreDrawBlockSize(final ShaderKey key) {
        return drawBlockSize(key);
    }

    int drawBlockSize(final Object token) {
        Block block = findBlock(token);
        return block != null && block.layout.stream().anyMatch(member -> isDynamicDrawUniform(member.name()))
                ? block.size
                : 0;
    }

    boolean requiresDynamicTransforms(final Object token) {
        Block block = findBlock(token);
        return usesMojangCoreTransforms(token)
                && block != null && block.layout.stream().anyMatch(member ->
                CORE_MODEL_VIEW_INVERSE.equals(member.name()) || CORE_NORMAL_MATRIX.equals(member.name()));
    }

    boolean requiresProjection(final Object token) {
        Block block = findBlock(token);
        return usesMojangCoreTransforms(token)
                && block != null && block.layout.stream().anyMatch(member ->
                CORE_PROJECTION_INVERSE.equals(member.name()));
    }

    void materializeCoreDraw(
            final ShaderKey key,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection
    ) {
        materializeCoreDraw(
                key,
                output,
                dynamicTransforms,
                projection,
                DrawUniformContext.empty()
        );
    }

    void materializeCoreDraw(
            final ShaderKey key,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection,
            final DrawUniformContext context
    ) {
        Block block = findBlock(key);
        if (block == null || block.staging == null) {
            throw new IllegalStateException("Iris core uniform block is not prepared for " + key);
        }
        materializeDrawUniforms(
                block.staging,
                block.layout,
                output,
                dynamicTransforms,
                projection,
                this.renderStageSource.getAsInt(),
                CapturedRenderingState.INSTANCE.getCurrentRenderedEntity(),
                CapturedRenderingState.INSTANCE.getTextureReloadCount(),
                context,
                true
        );
    }

    void materializeDraw(
            final Object token,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection
    ) {
        Block block = findBlock(token);
        if (block == null || block.staging == null) {
            throw new IllegalStateException("Iris uniform block is not prepared for " + token);
        }
        materializeDrawUniforms(
                block.staging,
                block.layout,
                output,
                dynamicTransforms,
                projection,
                this.renderStageSource.getAsInt(),
                CapturedRenderingState.INSTANCE.getCurrentRenderedEntity(),
                CapturedRenderingState.INSTANCE.getTextureReloadCount(),
                DrawUniformContext.empty(),
                usesMojangCoreTransforms(token)
        );
    }

    static void materializeCoreDrawUniforms(
            final ByteBuffer base,
            final List<IrisMetalGlslLinker.UniformMember> layout,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection
    ) {
        materializeCoreDrawUniforms(
                base,
                layout,
                output,
                dynamicTransforms,
                projection,
                0,
                CapturedRenderingState.INSTANCE.getCurrentRenderedEntity(),
                CapturedRenderingState.INSTANCE.getTextureReloadCount(),
                DrawUniformContext.empty()
        );
    }

    static void materializeCoreDrawUniforms(
            final ByteBuffer base,
            final List<IrisMetalGlslLinker.UniformMember> layout,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection,
            final int renderStage,
            final int entityId,
            final int textureReloadCount,
            final DrawUniformContext context
    ) {
        materializeDrawUniforms(
                base,
                layout,
                output,
                dynamicTransforms,
                projection,
                renderStage,
                entityId,
                textureReloadCount,
                context,
                true
        );
    }

    static void materializeDrawUniforms(
            final ByteBuffer base,
            final List<IrisMetalGlslLinker.UniformMember> layout,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection,
            final int renderStage
    ) {
        materializeDrawUniforms(
                base,
                layout,
                output,
                dynamicTransforms,
                projection,
                renderStage,
                CapturedRenderingState.INSTANCE.getCurrentRenderedEntity(),
                CapturedRenderingState.INSTANCE.getTextureReloadCount(),
                DrawUniformContext.empty(),
                false
        );
    }

    private static void materializeDrawUniforms(
            final ByteBuffer base,
            final List<IrisMetalGlslLinker.UniformMember> layout,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection,
            final int renderStage,
            final int entityId,
            final int textureReloadCount,
            final DrawUniformContext context,
            final boolean coreDraw
    ) {
        Objects.requireNonNull(context, "context");
        ByteBuffer destination = output.slice().order(output.order());
        ByteBuffer source = base.duplicate().order(base.order());
        source.clear();
        if (destination.remaining() < source.remaining()) {
            throw new IllegalArgumentException(
                    "Iris core transient block is " + destination.remaining()
                            + " bytes, expected at least " + source.remaining()
            );
        }
        destination.put(source);

        boolean needsModelView = coreDraw && layout.stream().anyMatch(member ->
                CORE_MODEL_VIEW_INVERSE.equals(member.name()) || CORE_NORMAL_MATRIX.equals(member.name()));
        boolean needsProjection = coreDraw
                && layout.stream().anyMatch(member -> CORE_PROJECTION_INVERSE.equals(member.name()));
        Matrix4f modelViewInverse = needsModelView
                ? readMat4(dynamicTransforms, "DynamicTransforms").invert()
                : null;
        Matrix4f projectionInverse = needsProjection
                ? MetalIrisDepthConvention.packProjection(readMat4(projection, "Projection")).invert()
                : null;
        Matrix3f normalMatrix = modelViewInverse == null
                ? null
                : modelViewInverse.transpose3x3(new Matrix3f());

        for (IrisMetalGlslLinker.UniformMember member : layout) {
            switch (member.name()) {
                case CORE_MODEL_VIEW_INVERSE -> {
                    if (coreDraw) {
                        requireCoreDrawType(member, "mat4");
                        putMat4(destination, member.offset(), Objects.requireNonNull(modelViewInverse));
                    }
                }
                case CORE_PROJECTION_INVERSE -> {
                    if (coreDraw) {
                        requireCoreDrawType(member, "mat4");
                        putMat4(destination, member.offset(), Objects.requireNonNull(projectionInverse));
                    }
                }
                case CORE_NORMAL_MATRIX -> {
                    if (coreDraw) {
                        requireCoreDrawType(member, "mat3");
                        putMat3(destination, member.offset(), Objects.requireNonNull(normalMatrix));
                    }
                }
                case "renderStage" -> {
                    requireDynamicDrawType(member, "int");
                    destination.putInt(member.offset(), renderStage);
                }
                case "entityId" -> {
                    requireDynamicDrawType(member, "int");
                    destination.putInt(member.offset(), entityId);
                }
                case "atlasSize" -> {
                    requireDynamicDrawType(member, "ivec2");
                    putIVec2(destination, member.offset(), context.atlasWidth(), context.atlasHeight());
                }
                case "gtextureId" -> {
                    requireDynamicDrawType(member, "int");
                    destination.putInt(member.offset(), logicalTextureId(context.gtexture()));
                }
                case "textureReloadCount" -> {
                    requireDynamicDrawType(member, "int");
                    destination.putInt(member.offset(), textureReloadCount);
                }
                case "gtextureSize" -> {
                    requireDynamicDrawType(member, "ivec2");
                    GpuTextureView texture = context.gtexture();
                    putIVec2(
                            destination,
                            member.offset(),
                            texture == null ? 0 : texture.getWidth(0),
                            texture == null ? 0 : texture.getHeight(0)
                    );
                }
                case "blendFunc" -> {
                    requireDynamicDrawType(member, "ivec4");
                    int[] blend = irisBlendFunc(context.blendFunction());
                    putIVec4(destination, member.offset(), blend[0], blend[1], blend[2], blend[3]);
                }
                default -> {
                }
            }
        }
    }

    private @Nullable Block findBlock(final Object token) {
        for (Block block : this.blocks) {
            if (block.token.equals(token)) {
                return block;
            }
        }
        return null;
    }

    /**
     * Iris identifies shadow Sodium terrain with {@link ShaderKey} constants,
     * but those programs still execute through Sodium's chunk draw and do not
     * bind Mojang's core {@code DynamicTransforms}/{@code Projection} blocks.
     */
    static boolean usesMojangCoreTransforms(final Object token) {
        if (!(token instanceof ShaderKey key)) {
            return false;
        }
        return key != ShaderKey.SODIUM_TERRAIN_SOLID
                && key != ShaderKey.SODIUM_TERRAIN_CUTOUT
                && key != ShaderKey.SODIUM_TERRAIN_TRANSLUCENT
                && key != ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID
                && key != ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT
                && key != ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT;
    }

    private static boolean isCoreDrawUniform(final String name) {
        return CORE_MODEL_VIEW_INVERSE.equals(name)
                || CORE_PROJECTION_INVERSE.equals(name)
                || CORE_NORMAL_MATRIX.equals(name);
    }

    private static boolean isDynamicDrawUniform(final String name) {
        return isCoreDrawUniform(name)
                || switch (name) {
                    case "entityId", "atlasSize", "gtextureId", "textureReloadCount",
                            "gtextureSize", "blendFunc", "renderStage" -> true;
                    default -> false;
                };
    }

    private static int logicalTextureId(final @Nullable GpuTextureView view) {
        if (view == null) {
            return 0;
        }
        if (!(view.texture() instanceof MetalGpuTexture texture)) {
            throw new IllegalStateException("Iris core draw texture is not backed by Metal");
        }
        return texture.iris$getGlId();
    }

    static int[] irisBlendFunc(final Optional<BlendFunction> blendFunction) {
        if (blendFunction.isEmpty()) {
            return new int[]{0, 0, 0, 0};
        }
        BlendFunction function = blendFunction.get();
        return new int[]{
                glBlendFactor(function.color().sourceFactor()),
                glBlendFactor(function.color().destFactor()),
                glBlendFactor(function.alpha().sourceFactor()),
                glBlendFactor(function.alpha().destFactor())
        };
    }

    private static int glBlendFactor(final BlendFactor factor) {
        return switch (factor) {
            case ZERO -> 0;
            case ONE -> 1;
            case SRC_COLOR -> 0x0300;
            case ONE_MINUS_SRC_COLOR -> 0x0301;
            case SRC_ALPHA -> 0x0302;
            case ONE_MINUS_SRC_ALPHA -> 0x0303;
            case DST_ALPHA -> 0x0304;
            case ONE_MINUS_DST_ALPHA -> 0x0305;
            case DST_COLOR -> 0x0306;
            case ONE_MINUS_DST_COLOR -> 0x0307;
            case SRC_ALPHA_SATURATE -> 0x0308;
            case CONSTANT_COLOR -> 0x8001;
            case ONE_MINUS_CONSTANT_COLOR -> 0x8002;
            case CONSTANT_ALPHA -> 0x8003;
            case ONE_MINUS_CONSTANT_ALPHA -> 0x8004;
        };
    }

    private static Matrix4f readMat4(final @Nullable ByteBuffer source, final String blockName) {
        if (source == null) {
            throw new IllegalStateException("Iris core draw requires bound " + blockName + " uniform data");
        }
        ByteBuffer data = source.duplicate().order(source.order());
        if (data.remaining() < 16 * Float.BYTES) {
            throw new IllegalStateException(
                    "Iris core draw " + blockName + " uniform is " + data.remaining()
                            + " bytes, expected at least " + (16 * Float.BYTES)
            );
        }
        return new Matrix4f().set(data.position(), data);
    }

    private static void requireCoreDrawType(
            final IrisMetalGlslLinker.UniformMember member,
            final String expected
    ) {
        if (member.arrayCount() != 0 || !expected.equals(member.type())) {
            throw new IllegalStateException(
                    "Iris core draw uniform '" + member.name() + "' must be " + expected
                            + ", got " + member.type() + (member.arrayCount() == 0 ? "" : "[]")
            );
        }
    }

    private static void requireDynamicDrawType(
            final IrisMetalGlslLinker.UniformMember member,
            final String expected
    ) {
        if (member.arrayCount() != 0 || !expected.equals(member.type())) {
            throw new IllegalStateException(
                    "Iris dynamic uniform '" + member.name() + "' must be " + expected
                            + ", got " + member.type() + (member.arrayCount() == 0 ? "" : "[]")
            );
        }
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
            float fogStart,
            float fogEnd,
            float tickDelta,
            float frameTime,
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
            if (this.strict) {
                throw new IllegalStateException("Could not sample Iris frame uniforms", t);
            }
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
        SystemFrameTime systemTime = systemFrameTime();
        return new Frame(
                new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix3f(),
                new Vector3d(),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, -100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector3d(), 0.0f, 0.0f, 256.0f, 0.0f, systemTime.frameTime(),
                0.25f, 0.25f, 0.0f, 1.0f, 1.0f, 1.0f, 256.0f,
                systemTime.frameTimeCounter(), 0, 0, systemTime.frameCounter()
        );
    }

    private Frame sampleLiveFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        ClientLevel level = minecraft.level;

        Matrix4f modelView = new Matrix4f(state.getGbufferModelView());
        Matrix4f projection = MetalIrisDepthConvention.packProjection(state.getGbufferProjection());
        warnIfUnfilled(modelView, projection);

        Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
        Matrix4f projectionInverse = new Matrix4f(projection).invert();
        Matrix3f normalMatrix = new Matrix3f(modelView).invert().transpose();

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 cameraPos = camera == null ? Vec3.ZERO : camera.position();
        Vector3d cameraPosition = new Vector3d(cameraPos.x, cameraPos.y, cameraPos.z);

        float sunAngle = CelestialUniforms.getSunAngle(true) / 360.0f;
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
        SystemFrameTime systemTime = systemFrameTime();
        int renderDistance = minecraft.options == null ? 8 : minecraft.options.getEffectiveRenderDistance();
        var mainTarget = minecraft.gameRenderer.mainRenderTarget();
        var fogParameters = ((FogStorage) minecraft.gameRenderer).sodium$getFogParameters();

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
                fogParameters.environmentalStart(),
                fogParameters.environmentalEnd(),
                tickDelta,
                systemTime.frameTime(),
                sunAngle,
                CelestialUniforms.getSunAngle(day) / 360.0f,
                level == null ? 0.0f : level.getRainLevel(tickDelta),
                minecraft.options == null ? 1.0f : minecraft.options.gamma().get().floatValue(),
                mainTarget.width,
                mainTarget.height,
                renderDistance * 16.0f,
                systemTime.frameTimeCounter(),
                level == null ? 0 : (int) (level.getDefaultClockTime() % 24000L),
                level == null ? 0 : (int) (level.getDefaultClockTime() / 24000L),
                systemTime.frameCounter()
        );
    }

    /**
     * Reads the same timer and counter objects that native Iris registers in
     * {@code SystemTimeUniforms.addSystemTimeUniforms}. Iris advances them from
     * its {@code MixinGameRenderer} at the start of every rendered frame.
     */
    static SystemFrameTime systemFrameTime() {
        return new SystemFrameTime(
                SystemTimeUniforms.TIMER.getLastFrameTime(),
                SystemTimeUniforms.TIMER.getFrameTimeCounter(),
                SystemTimeUniforms.COUNTER.getAsInt()
        );
    }

    record SystemFrameTime(float frameTime, float frameTimeCounter, int frameCounter) {
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

    private void write(
            final ByteBuffer out,
            final IrisMetalGlslLinker.UniformMember member,
            final Frame frame,
            final OptionalDouble alphaTestReference
    ) {
        if (writeOfficialUniform(out, member, alphaTestReference)) {
            return;
        }
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

            // --- externally-managed Mojang/Sodium fog state ---
            case "fogColor", "skyColor" -> putVec3(out, at, frame.fogColor());
            case "iris_FogColor" ->
                    putVec4(out, at, (float) frame.fogColor().x, (float) frame.fogColor().y, (float) frame.fogColor().z, 1.0f);
            case "fogDensity", "iris_FogDensity" -> out.putFloat(at, frame.fogDensity());
            case "fogStart", "iris_FogStart" -> out.putFloat(at, frame.fogStart());
            case "fogEnd", "iris_FogEnd" -> out.putFloat(at, frame.fogEnd());

            // --- time (exact) ---
            case "frameTimeCounter" -> out.putFloat(at, frame.frameTimeCounter());
            case "frameTime" -> out.putFloat(at, frame.frameTime());
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

    /** Writes a value evaluated by Iris's own fixed/custom uniform graph. */
    boolean writeOfficialUniform(
            final ByteBuffer out,
            final IrisMetalGlslLinker.UniformMember member
    ) {
        return writeOfficialUniform(out, member, OptionalDouble.empty());
    }

    private boolean writeOfficialUniform(
            final ByteBuffer out,
            final IrisMetalGlslLinker.UniformMember member,
            final OptionalDouble alphaTestReference
    ) {
        if ("renderStage".equals(member.name())) {
            requireDynamicDrawType(member, "int");
            // Iris 1.11.2 CommonUniforms reads
            // GbufferPrograms.getCurrentPhase().ordinal(). The owning Metal
            // pipeline supplies the same WorldRenderingPhase state directly.
            out.putInt(member.offset(), this.renderStageSource.getAsInt());
            return true;
        }
        if ("iris_currentAlphaTest".equals(member.name())) {
            if (member.arrayCount() != 0 || !"float".equals(member.type())) {
                throw new IllegalStateException(
                        "Iris internal uniform 'iris_currentAlphaTest' must be float, got "
                                + member.type() + (member.arrayCount() == 0 ? "" : "[]")
                );
            }
            out.putFloat(
                    member.offset(),
                    (float) alphaTestReference.orElseGet(
                            CapturedRenderingState.INSTANCE::getCurrentAlphaTest
                    )
            );
            return true;
        }
        if ("iris_LightmapTextureMatrix".equals(member.name())) {
            if (member.arrayCount() != 0 || !"mat4".equals(member.type())) {
                throw new IllegalStateException(
                        "Iris built-in uniform 'iris_LightmapTextureMatrix' must be mat4, got "
                                + member.type() + (member.arrayCount() == 0 ? "" : "[]")
                );
            }
            // Sodium supplies unpacked light coordinates in [0, 240]. Iris's
            // built-in replacement maps them to the centers of the 16 texels.
            putMat4(out, member.offset(), LIGHTMAP_TEXTURE_MATRIX);
            return true;
        }
        if (this.customUniforms == null || !this.customUniforms.hasVariable(member.name())) {
            return false;
        }
        // UniformMember uses 0 for an ordinary scalar/vector/matrix and a
        // positive value only for an explicit GLSL array declarator.
        if (member.arrayCount() > 0) {
            throw new IllegalStateException(
                    "Iris uniform graph cannot supply array member '" + member.name()
                            + "' (count=" + member.arrayCount() + ")"
            );
        }

        FunctionReturn value = new FunctionReturn();
        this.customUniforms.getVariable(member.name()).evaluateTo(this.customUniforms, value);
        int at = member.offset();
        switch (member.type()) {
            case "bool" -> out.putInt(at, value.booleanReturn ? 1 : 0);
            case "int" -> out.putInt(at, value.intReturn);
            case "float" -> out.putFloat(at, value.floatReturn);
            case "vec2" -> {
                Vector2f vector = customObject(member, value, Vector2f.class);
                putVec2(out, at, vector.x, vector.y);
            }
            case "vec3" -> {
                Vector3f vector = customObject(member, value, Vector3f.class);
                putVec3(out, at, vector.x, vector.y, vector.z);
            }
            case "vec4" -> {
                Vector4f vector = customObject(member, value, Vector4f.class);
                putVec4(out, at, vector.x, vector.y, vector.z, vector.w);
            }
            case "ivec2" -> {
                Vector2i vector = customObject(member, value, Vector2i.class);
                putIVec2(out, at, vector.x, vector.y);
            }
            case "ivec3" -> {
                Vector3i vector = customObject(member, value, Vector3i.class);
                putIVec3(out, at, vector.x, vector.y, vector.z);
            }
            case "ivec4" -> {
                Vector4i vector = customObject(member, value, Vector4i.class);
                putIVec4(out, at, vector.x, vector.y, vector.z, vector.w);
            }
            case "mat4" -> putMat4(
                    out,
                    at,
                    packProjectionUniform(member.name(), customObject(member, value, Matrix4fc.class))
            );
            default -> throw new IllegalStateException(
                    "Iris uniform graph produced unsupported GLSL type '" + member.type()
                            + "' for '" + member.name() + "'"
            );
        }
        return true;
    }

    private static Matrix4fc packProjectionUniform(final String name, final Matrix4fc value) {
        return switch (name) {
            case "gbufferProjection", "gbufferPreviousProjection", "iris_ProjectionMatrix" ->
                    MetalIrisDepthConvention.packProjection(value);
            case "gbufferProjectionInverse", "iris_ProjectionMatrixInverse" ->
                    MetalIrisDepthConvention.packProjectionInverse(value);
            default -> value;
        };
    }

    private static <T> T customObject(
            final IrisMetalGlslLinker.UniformMember member,
            final FunctionReturn value,
            final Class<T> expected
    ) {
        if (!expected.isInstance(value.objectReturn)) {
            throw new IllegalStateException(
                    "Iris uniform '" + member.name() + "' (" + member.type() + ") evaluated to "
                            + (value.objectReturn == null ? "null" : value.objectReturn.getClass().getName())
                            + ", expected " + expected.getName()
            );
        }
        return expected.cast(value.objectReturn);
    }

    private void reportUnsupported(final ByteBuffer out, final IrisMetalGlslLinker.UniformMember member) {
        if (this.strict) {
            throw new IllegalStateException(
                    "Iris uniform '" + member.name() + "' (" + member.type()
                            + ") has no Metal or Iris value source"
            );
        }
        // Translation-only tests deliberately use the legacy relaxed constructor.
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
    private static void putMat4(final ByteBuffer out, final int offset, final Matrix4fc matrix) {
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

    private static void putVec2(final ByteBuffer out, final int offset, final float x, final float y) {
        out.putFloat(offset, x);
        out.putFloat(offset + 4, y);
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

    private static void putIVec3(
            final ByteBuffer out,
            final int offset,
            final int x,
            final int y,
            final int z
    ) {
        putIVec2(out, offset, x, y);
        out.putInt(offset + 8, z);
    }

    private static void putIVec4(
            final ByteBuffer out,
            final int offset,
            final int x,
            final int y,
            final int z,
            final int w
    ) {
        putIVec3(out, offset, x, y, z);
        out.putInt(offset + 12, w);
    }
}
