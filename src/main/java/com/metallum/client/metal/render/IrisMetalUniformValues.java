package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import kroppeb.stareval.function.FunctionReturn;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final Field CUSTOM_UNIFORM_ORDER = customUniformOrderField();
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
    private final @Nullable CustomUniformFixedInputUniformsHolder fixedInputs;
    private final @Nullable IrisMetalDynamicUniforms dynamicUniforms;
    private final @Nullable FrameUpdateNotifier updateNotifier;
    private final IntSupplier renderStageSource;
    private final boolean strict;
    private final List<Block> blocks = new ArrayList<>();
    private final Set<String> unsupported = new HashSet<>();
    private final Matrix4f previousModelView = new Matrix4f();
    private final Matrix4f previousProjection = new Matrix4f();
    private final Vector3d previousCameraPosition = new Vector3d();
    private @Nullable Frame lastFrame;
    private @Nullable Frame shadowRestoreFrame;
    private boolean warnedIdentityMatrices;
    private boolean closed;

    /** A candidate generation can register blocks before its first PSO fails. */
    record RegistrationCheckpoint(int blockCount) {
        RegistrationCheckpoint {
            if (blockCount < 0) {
                throw new IllegalArgumentException("Uniform registration checkpoint must be non-negative");
            }
        }
    }

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
            Backing backing = new Backing(device, this.label, this.size);
            this.device = backing.device;
            this.buffer = backing.buffer;
            this.staging = backing.staging;
        }
    }

    /** One GPU backing for a logical uniform block. */
    private static final class Backing {
        private final MetalDevice device;
        private final GpuBuffer buffer;
        private final ByteBuffer staging;

        private Backing(final MetalDevice device, final String label, final int size) {
            this.device = Objects.requireNonNull(device, "device");
            this.buffer = device.createBuffer(
                    () -> "metallum:iris_uniforms/" + label,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    size
            );
            try {
                // writeToBuffer rejects heap buffers (they would SIGBUS in the
                // staging path), so the scratch has to be direct.
                this.staging = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
            } catch (RuntimeException | Error failure) {
                this.buffer.close();
                throw failure;
            }
        }

        private void close() {
            this.buffer.close();
        }
    }

    /**
     * Candidate-only GPU backings used while a device replacement is being
     * prepared. The active block fields are deliberately untouched until
     * {@link #commit()} succeeds, so a failed graph/PSO/binding candidate cannot
     * invalidate the generation that is still rendering.
     */
    final class BackingTransaction implements AutoCloseable {
        private final MetalDevice device;
        private final IdentityHashMap<Block, Backing> candidates = new IdentityHashMap<>();
        private boolean committed;
        private boolean closed;

        private BackingTransaction(final MetalDevice device) {
            this.device = Objects.requireNonNull(device, "device");
        }

        private Backing ensure(final Block block) {
            if (this.closed || this.committed) {
                throw new IllegalStateException("Iris uniform backing transaction is no longer open");
            }
            return this.candidates.computeIfAbsent(
                    block, ignored -> new Backing(this.device, block.label, block.size)
            );
        }

        int candidateCount() {
            return this.candidates.size();
        }

        boolean isCommitted() {
            return this.committed;
        }

        void commit() {
            if (this.closed) {
                throw new IllegalStateException("Iris uniform backing transaction is closed");
            }
            if (this.committed) {
                return;
            }
            List<GpuBuffer> retired = new ArrayList<>(this.candidates.size());
            for (Map.Entry<Block, Backing> entry : this.candidates.entrySet()) {
                Block block = entry.getKey();
                Backing candidate = entry.getValue();
                if (block.buffer != null) {
                    retired.add(block.buffer);
                }
                block.device = candidate.device;
                block.buffer = candidate.buffer;
                block.staging = candidate.staging;
            }
            this.candidates.clear();
            this.committed = true;

            // Retiring old buffers must never turn a successful candidate into
            // a failed publication. The backend close path is already
            // idempotent and queues the native release on the owning device.
            for (GpuBuffer old : retired) {
                try {
                    old.close();
                } catch (RuntimeException | Error failure) {
                    Metallum.LOGGER.warn(
                            "[metallum-iris] failed to retire a replaced uniform backing", failure
                    );
                }
            }
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            if (this.committed) {
                return;
            }
            this.candidates.values().forEach(Backing::close);
            this.candidates.clear();
        }
    }

    IrisMetalUniformValues(final float sunPathRotation) {
        this(sunPathRotation, null, null, null, null, () -> 0, false);
    }

    IrisMetalUniformValues(final float sunPathRotation, final IntSupplier renderStageSource) {
        this(sunPathRotation, null, null, null, null, renderStageSource, false);
    }

    IrisMetalUniformValues(
            final float sunPathRotation,
            final CustomUniforms customUniforms,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource
    ) {
        this(sunPathRotation, customUniforms, null, null, updateNotifier, renderStageSource, true);
    }

    IrisMetalUniformValues(
            final float sunPathRotation,
            final CustomUniforms customUniforms,
            final CustomUniformFixedInputUniformsHolder fixedInputs,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource
    ) {
        this(sunPathRotation, customUniforms, fixedInputs, null, updateNotifier, renderStageSource, true);
    }

    IrisMetalUniformValues(
            final float sunPathRotation,
            final CustomUniforms customUniforms,
            final CustomUniformFixedInputUniformsHolder fixedInputs,
            final IrisMetalDynamicUniforms dynamicUniforms,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource
    ) {
        this(sunPathRotation, customUniforms, fixedInputs, dynamicUniforms, updateNotifier, renderStageSource, true);
    }

    private IrisMetalUniformValues(
            final float sunPathRotation,
            final @Nullable CustomUniforms customUniforms,
            final @Nullable CustomUniformFixedInputUniformsHolder fixedInputs,
            final @Nullable IrisMetalDynamicUniforms dynamicUniforms,
            final @Nullable FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource,
            final boolean strict
    ) {
        if ((customUniforms == null) != (updateNotifier == null)) {
            throw new IllegalArgumentException("Iris custom uniforms and frame notifier must be supplied together");
        }
        this.sunPathRotation = sunPathRotation;
        this.customUniforms = customUniforms;
        this.fixedInputs = fixedInputs;
        this.dynamicUniforms = dynamicUniforms;
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
        if (this.strict) {
            requireUniformSources(token, program.uniformLayout());
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

    RegistrationCheckpoint checkpoint() {
        return new RegistrationCheckpoint(this.blocks.size());
    }

    BackingTransaction beginBackingTransaction(final MetalDevice device) {
        if (this.closed) {
            throw new IllegalStateException("Iris uniform values are closed");
        }
        return new BackingTransaction(device);
    }

    void rollback(final RegistrationCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.blockCount() > this.blocks.size()) {
            throw new IllegalArgumentException("Uniform registration checkpoint is from a future state");
        }
        while (this.blocks.size() > checkpoint.blockCount()) {
            Block block = this.blocks.remove(this.blocks.size() - 1);
            if (block.buffer != null) {
                block.buffer.close();
                block.buffer = null;
                block.staging = null;
                block.device = null;
            }
        }
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
        prewarm(device, null);
    }

    void prewarm(
            final MetalDevice device,
            final @Nullable BackingTransaction transaction
    ) {
        if (this.closed || this.blocks.isEmpty()) {
            return;
        }
        if (transaction != null && transaction.device != device) {
            throw new IllegalArgumentException(
                    "Iris uniform backing transaction belongs to another Metal device"
            );
        }
        Frame frame = null;
        for (Block block : this.blocks) {
            if (transaction != null) {
                Backing candidate = transaction.ensure(block);
                if (frame == null) {
                    frame = neutralFrame();
                }
                upload(block, candidate, frame);
                continue;
            }
            if (block.buffer != null && block.device != device) {
                throw new IllegalStateException(
                        "Iris uniform backing crossed Metal devices without a candidate transaction"
                );
            }
            if (block.buffer != null) {
                continue;
            }
            block.allocate(device);
            if (frame == null) {
                // Generation prewarm runs while Iris is still on the title
                // screen, before Minecraft has captured its first world
                // projection/model-view pair. These bytes are overwritten by
                // updateFrame() before the first owned draw; using the
                // explicit neutral frame here keeps prewarm independent of
                // world-render initialization without hiding live-frame
                // failures during actual rendering.
                frame = neutralFrame();
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
                if (this.fixedInputs != null) {
                    updateUnvisitedFixedInputs(this.customUniforms, this.fixedInputs);
                }
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
        this.lastFrame = frame;
        for (Block block : this.blocks) {
            if (block.buffer != null) {
                upload(block, frame);
            }
        }
        this.previousModelView.set(frame.modelView());
        this.previousProjection.set(frame.projection());
        this.previousCameraPosition.set(frame.cameraPosition());
    }

    /**
     * Updates fixed inputs that are not already part of CustomUniforms's
     * dependency order. Iris's custom graph updates those dependencies while
     * evaluating its order; updating the complete fixed-input holder first
     * would advance stateful suppliers twice, which breaks previous/history
     * uniforms.
     *
     * <p>The order is private in the pinned Iris build. Treat a changed field
     * name, type, or element type as an admission failure instead of silently
     * using stale values.</p>
     */
    static void updateUnvisitedFixedInputs(
            final CustomUniforms customUniforms,
            final CustomUniformFixedInputUniformsHolder fixedInputs
    ) {
        Set<CachedUniform> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Object order = CUSTOM_UNIFORM_ORDER.get(customUniforms);
            if (!(order instanceof Collection<?> collection)) {
                throw new IllegalStateException(
                        "Iris CustomUniforms.uniformOrder is not a collection: "
                                + (order == null ? "null" : order.getClass().getName())
                );
            }
            for (Object entry : collection) {
                if (!(entry instanceof CachedUniform uniform)) {
                    throw new IllegalStateException(
                            "Iris CustomUniforms.uniformOrder contains "
                                    + (entry == null ? "null" : entry.getClass().getName())
                    );
                }
                visited.add(uniform);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException(
                    "Could not inspect Iris 1.11.2 CustomUniforms.uniformOrder", failure
            );
        }
        for (CachedUniform uniform : fixedInputs.getAll()) {
            if (!visited.contains(uniform)) {
                uniform.update();
            }
        }
    }

    private static Field customUniformOrderField() {
        try {
            Field field = CustomUniforms.class.getDeclaredField("uniformOrder");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /**
     * Rebinds the generation-owned frame blocks to the matrices of the active
     * Iris shadow scene. Sodium's shadow renderer updates its own matrices,
     * but the Iris uniform block is backend-owned and otherwise keeps the
     * main-world camera transform for the whole frame.
     */
    void enterShadowFrame(final Matrix4f shadowView, final Matrix4f shadowProjection) {
        if (this.closed) {
            throw new IllegalStateException("Iris uniform values are closed");
        }
        if (this.shadowRestoreFrame != null) {
            throw new IllegalStateException("Iris shadow uniform frame was entered twice");
        }
        Frame worldFrame = Objects.requireNonNull(
                this.lastFrame,
                "Iris shadow uniforms require a completed world-frame upload"
        );
        Frame shadowFrame = withShadowMatrices(worldFrame, shadowView, shadowProjection);
        this.shadowRestoreFrame = worldFrame;
        try {
            for (Block block : this.blocks) {
                if (block.buffer != null) {
                    upload(block, shadowFrame);
                }
            }
        } catch (RuntimeException | Error failure) {
            try {
                restoreShadowFrame();
            } catch (RuntimeException | Error restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    /** Restores the main-world frame after a shadow scene, including failures. */
    void exitShadowFrame() {
        if (this.shadowRestoreFrame == null) {
            return;
        }
        restoreShadowFrame();
    }

    private void restoreShadowFrame() {
        Frame worldFrame = this.shadowRestoreFrame;
        if (worldFrame == null) {
            return;
        }
        try {
            for (Block block : this.blocks) {
                if (block.buffer != null) {
                    upload(block, worldFrame);
                }
            }
        } finally {
            this.shadowRestoreFrame = null;
        }
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
        upload(
                block,
                Objects.requireNonNull(block.buffer, "uniform buffer"),
                Objects.requireNonNull(block.staging, "uniform staging"),
                Objects.requireNonNull(block.device, "uniform device"),
                frame
        );
    }

    private void upload(final Block block, final Backing backing, final Frame frame) {
        upload(block, backing.buffer, backing.staging, backing.device, frame);
    }

    private void upload(
            final Block block,
            final GpuBuffer buffer,
            final ByteBuffer staging,
            final MetalDevice device,
            final Frame frame
    ) {
        zero(staging);
        for (IrisMetalGlslLinker.UniformMember member : block.layout) {
            if (isDynamicDrawUniform(member.name())) {
                continue;
            }
            write(staging, member, frame, block.alphaTestReference);
        }
        staging.rewind();
        device.createCommandEncoder().writeToBuffer(buffer.slice(), staging);
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
                this.dynamicUniforms,
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
                this.dynamicUniforms,
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
                null,
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
                null,
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
            final @Nullable IrisMetalDynamicUniforms dynamicUniforms,
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
        refreshLiveFogUniforms(destination, layout);

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
            if (dynamicUniforms != null && dynamicUniforms.write(member, destination, context)) {
                continue;
            }
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
                || isLiveFogUniform(name)
                || switch (name) {
                    case "entityId", "atlasSize", "gtextureId", "textureReloadCount",
                            "gtextureSize", "blendFunc", "renderStage", "fogMode", "fogShape",
                            "iris_currentAlphaTest", "alphaTestRef" -> true;
                    default -> false;
                };
    }

    private static boolean isFrameDerivedUniform(final String name) {
        return switch (name) {
            case "gbufferModelView", "gbufferModelViewInverse", "iris_ModelViewMatrix",
                    "iris_ModelViewMatrixInverse", "shadowModelView", "shadowModelViewInverse",
                    "gbufferProjection", "gbufferProjectionInverse", "iris_ProjectionMatrix",
                    "iris_ProjectionMatrixInverse", "shadowProjection", "shadowProjectionInverse",
                    "iris_ModelViewMatInverse", "iris_ProjMatInverse",
                    "gbufferPreviousModelView", "gbufferPreviousProjection", "iris_NormalMat",
                    "normalMatrix" -> true;
            default -> false;
        };
    }

    /**
     * Strict production blocks may only contain members with a real Iris
     * fixed/custom supplier, a real Iris dynamic supplier, or an explicitly
     * backend-owned draw/frame value. Relaxed layout tests intentionally keep
     * the old zero-fill behavior; active packs never do.
     */
    private void requireUniformSources(
            final Object token,
            final List<IrisMetalGlslLinker.UniformMember> layout
    ) {
        for (IrisMetalGlslLinker.UniformMember member : layout) {
            String name = member.name();
            if (this.customUniforms != null && this.customUniforms.hasVariable(name)) {
                continue;
            }
            if (this.fixedInputs != null && this.fixedInputs.containsKey(name)) {
                continue;
            }
            if (this.dynamicUniforms != null && this.dynamicUniforms.canMaterialize(member)) {
                continue;
            }
            if (isBackendOwnedUniform(token, member)) {
                continue;
            }
            throw new IllegalStateException(
                    "Iris uniform '" + name + "' (" + member.type()
                            + ") is absent from the fixed/custom/dynamic supplier graph"
            );
        }
    }

    private static boolean isBackendOwnedUniform(
            final Object token,
            final IrisMetalGlslLinker.UniformMember member
    ) {
        String name = member.name();
        if ("iris_LightmapTextureMatrix".equals(name)) {
            return member.arrayCount() == 0 && "mat4".equals(member.type());
        }
        if ("renderStage".equals(name)) {
            return member.arrayCount() == 0 && "int".equals(member.type());
        }
        if ("iris_currentAlphaTest".equals(name)) {
            return member.arrayCount() == 0 && "float".equals(member.type());
        }
        if (isCoreDrawUniform(name)) {
            boolean validType = member.arrayCount() == 0
                    && ((CORE_MODEL_VIEW_INVERSE.equals(name) || CORE_PROJECTION_INVERSE.equals(name))
                    ? "mat4".equals(member.type())
                    : "mat3".equals(member.type()));
            // Mojang core draws source these Iris aliases from transient
            // DynamicTransforms/Projection blocks. Sodium terrain has no such
            // blocks, so the same ABI members are written from the captured
            // frame below. Both paths are real sources; neither is a zero-fill.
            return validType && (usesMojangCoreTransforms(token) || isFrameDerivedMatrix(member));
        }
        if (isFrameDerivedMatrix(member)) {
            return true;
        }
        return isLiveFogUniform(member.name()) && liveFogTypeMatches(member);
    }

    private static boolean isFrameDerivedMatrix(
            final IrisMetalGlslLinker.UniformMember member
    ) {
        if (member.arrayCount() != 0) {
            return false;
        }
        return switch (member.name()) {
            case "gbufferModelView", "gbufferModelViewInverse", "iris_ModelViewMatrix",
                    "iris_ModelViewMatrixInverse", "shadowModelView", "shadowModelViewInverse",
                    "gbufferProjection", "gbufferProjectionInverse", "iris_ProjectionMatrix",
                    "iris_ProjectionMatrixInverse", "iris_ModelViewMatInverse", "iris_ProjMatInverse",
                    "shadowProjection", "shadowProjectionInverse",
                    "gbufferPreviousModelView", "gbufferPreviousProjection" -> "mat4".equals(member.type());
            case "iris_NormalMat", "normalMatrix" -> "mat3".equals(member.type());
            default -> false;
        };
    }

    private static boolean liveFogTypeMatches(final IrisMetalGlslLinker.UniformMember member) {
        return switch (member.name()) {
            case "fogColor", "skyColor" -> member.arrayCount() == 0 && "vec3".equals(member.type());
            case "iris_FogColor" -> member.arrayCount() == 0 && "vec4".equals(member.type());
            case "fogDensity", "fogStart", "fogEnd", "iris_FogDensity", "iris_FogStart", "iris_FogEnd" ->
                    member.arrayCount() == 0 && "float".equals(member.type());
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

    static int logicalTextureIdForDynamic(final GpuTextureView view) {
        return logicalTextureId(view);
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

    private static boolean isLiveFogUniform(final String name) {
        return switch (name) {
            case "fogColor", "skyColor", "fogDensity", "fogStart", "fogEnd",
                    "iris_FogColor", "iris_FogDensity", "iris_FogStart", "iris_FogEnd" -> true;
            default -> false;
        };
    }

    private static void refreshLiveFogUniforms(
            final ByteBuffer destination,
            final List<IrisMetalGlslLinker.UniformMember> layout
    ) {
        if (layout.stream().noneMatch(member -> isLiveFogUniform(member.name()))) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        FogParameters parameters = liveFogParameters(minecraft);
        Vector3d capturedColor = CapturedRenderingState.INSTANCE.getFogColor();
        writeLiveFogUniforms(
                destination,
                layout,
                parameters,
                capturedColor,
                CapturedRenderingState.INSTANCE.getFogDensity()
        );
    }

    private static @Nullable FogParameters liveFogParameters(final @Nullable Minecraft minecraft) {
        if (minecraft != null && minecraft.gameRenderer != null) {
            var cameraState = minecraft.gameRenderer.gameRenderState()
                    .levelRenderState.cameraRenderState;
            if (cameraState != null && cameraState.initialized
                    && cameraState.fogData != null && cameraState.fogData.color != null) {
                return fogParameters(cameraState.fogData);
            }
            if (minecraft.gameRenderer instanceof FogStorage fogStorage) {
                return fogStorage.sodium$getFogParameters();
            }
        }
        return null;
    }

    static FogParameters fogParameters(final FogData data) {
        Vector4f color = Objects.requireNonNull(data.color, "fog color");
        return new FogParameters(
                color.x,
                color.y,
                color.z,
                color.w,
                data.environmentalStart,
                data.environmentalEnd,
                data.renderDistanceStart,
                data.renderDistanceEnd
        );
    }

    static void writeLiveFogUniforms(
            final ByteBuffer destination,
            final List<IrisMetalGlslLinker.UniformMember> layout,
            final @Nullable FogParameters parameters,
            final Vector3d capturedColor,
            final float capturedDensity
    ) {
        Vector4f irisColor = parameters == null
                ? new Vector4f((float) capturedColor.x, (float) capturedColor.y, (float) capturedColor.z, 1.0F)
                : new Vector4f(parameters.red(), parameters.green(), parameters.blue(), parameters.alpha());
        for (IrisMetalGlslLinker.UniformMember member : layout) {
            int offset = member.offset();
            switch (member.name()) {
                case "fogColor", "skyColor" -> {
                    requireDynamicDrawType(member, "vec3");
                    putVec3(destination, offset, capturedColor);
                }
                case "fogDensity", "iris_FogDensity" -> {
                    requireDynamicDrawType(member, "float");
                    destination.putFloat(offset, capturedDensity);
                }
                case "iris_FogColor" -> {
                    requireDynamicDrawType(member, "vec4");
                    putVec4(destination, offset, irisColor.x, irisColor.y, irisColor.z, irisColor.w);
                }
                case "fogStart", "iris_FogStart" -> {
                    if (parameters != null) {
                        requireDynamicDrawType(member, "float");
                        destination.putFloat(offset, parameters.environmentalStart());
                    }
                }
                case "fogEnd", "iris_FogEnd" -> {
                    if (parameters != null) {
                        requireDynamicDrawType(member, "float");
                        destination.putFloat(offset, parameters.environmentalEnd());
                    }
                }
                default -> {
                }
            }
        }
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

    private static Frame withShadowMatrices(
            final Frame world,
            final Matrix4f shadowView,
            final Matrix4f shadowProjection
    ) {
        Matrix4f view = new Matrix4f(shadowView);
        Matrix4f projection = MetalIrisDepthConvention.packProjection(shadowProjection);
        return new Frame(
                view,
                new Matrix4f(view).invert(),
                projection,
                new Matrix4f(projection).invert(),
                new Matrix4f(view).invert().transpose3x3(new Matrix3f()),
                new Vector3d(world.cameraPosition()),
                new Vector4f(world.sunPosition()),
                new Vector4f(world.moonPosition()),
                new Vector4f(world.shadowLightPosition()),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f).mul(view),
                new Vector3d(world.fogColor()),
                world.fogDensity(),
                world.fogStart(),
                world.fogEnd(),
                world.tickDelta(),
                world.frameTime(),
                world.sunAngle(),
                world.shadowAngle(),
                world.rainStrength(),
                world.screenBrightness(),
                world.viewWidth(),
                world.viewHeight(),
                world.far(),
                world.frameTimeCounter(),
                world.worldTime(),
                world.worldDay(),
                world.frameCounter()
        );
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
        if (isFrameDerivedUniform(member.name())) {
            writeFrameDerivedUniform(out, member, frame);
            return;
        }
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

    private void writeFrameDerivedUniform(
            final ByteBuffer out,
            final IrisMetalGlslLinker.UniformMember member,
            final Frame frame
    ) {
        int at = member.offset();
        switch (member.name()) {
            case "gbufferModelView", "iris_ModelViewMatrix", "shadowModelView" -> {
                requireFrameType(member, "mat4");
                putMat4(out, at, frame.modelView());
            }
            case "gbufferModelViewInverse", "iris_ModelViewMatrixInverse", "iris_ModelViewMatInverse",
                    "shadowModelViewInverse" -> {
                requireFrameType(member, "mat4");
                putMat4(out, at, frame.modelViewInverse());
            }
            case "gbufferProjection", "iris_ProjectionMatrix", "shadowProjection" -> {
                requireFrameType(member, "mat4");
                putMat4(out, at, frame.projection());
            }
            case "gbufferProjectionInverse", "iris_ProjectionMatrixInverse", "iris_ProjMatInverse",
                    "shadowProjectionInverse" -> {
                requireFrameType(member, "mat4");
                putMat4(out, at, frame.projectionInverse());
            }
            case "gbufferPreviousModelView" -> {
                requireFrameType(member, "mat4");
                putMat4(out, at, this.previousModelView);
            }
            case "gbufferPreviousProjection" -> {
                requireFrameType(member, "mat4");
                putMat4(out, at, this.previousProjection);
            }
            case "iris_NormalMat", "normalMatrix" -> {
                requireFrameType(member, "mat3");
                putMat3(out, at, frame.normalMatrix());
            }
            default -> throw new AssertionError("Unknown frame-derived Iris uniform " + member.name());
        }
    }

    private static void requireFrameType(
            final IrisMetalGlslLinker.UniformMember member,
            final String expected
    ) {
        if (member.arrayCount() != 0 || !expected.equals(member.type())) {
            throw new IllegalStateException(
                    "Iris frame uniform '" + member.name() + "' must be " + expected
                            + ", got " + member.type()
                            + (member.arrayCount() == 0 ? "" : "[]")
            );
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
            boolean written = writeFixedInput(out, member);
            if (!written) {
                // Strict production generations reject this at registration,
                // but keep the write boundary fail-closed as well so a
                // malformed candidate cannot zero-fill an active uniform.
                reportUnsupported(out, member);
            }
            return written;
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
                Vector3f vector = uniformVector3(member, value.objectReturn, "Iris uniform");
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

    private boolean writeFixedInput(
            final ByteBuffer out,
            final IrisMetalGlslLinker.UniformMember member
    ) {
        if (this.fixedInputs == null || !this.fixedInputs.containsKey(member.name())) {
            return false;
        }
        if (member.arrayCount() > 0) {
            throw new IllegalStateException(
                    "Iris fixed uniform graph cannot supply array member '" + member.name()
                            + "' (count=" + member.arrayCount() + ")"
            );
        }
        CachedUniform uniform = this.fixedInputs.getUniform(member.name());
        FunctionReturn value = new FunctionReturn();
        uniform.writeTo(value);
        int at = member.offset();
        switch (member.type()) {
            case "bool" -> out.putInt(at, value.booleanReturn ? 1 : 0);
            case "int" -> out.putInt(at, value.intReturn);
            case "float" -> out.putFloat(at, value.floatReturn);
            case "vec2" -> {
                Vector2f vector = fixedObject(member, value, Vector2f.class);
                putVec2(out, at, vector.x, vector.y);
            }
            case "vec3" -> {
                Vector3f vector = uniformVector3(member, value.objectReturn, "Iris fixed uniform");
                putVec3(out, at, vector.x, vector.y, vector.z);
            }
            case "vec4" -> {
                Vector4f vector = fixedObject(member, value, Vector4f.class);
                putVec4(out, at, vector.x, vector.y, vector.z, vector.w);
            }
            case "ivec2" -> {
                Vector2i vector = fixedObject(member, value, Vector2i.class);
                putIVec2(out, at, vector.x, vector.y);
            }
            case "ivec3" -> {
                Vector3i vector = fixedObject(member, value, Vector3i.class);
                putIVec3(out, at, vector.x, vector.y, vector.z);
            }
            case "ivec4" -> {
                Vector4i vector = fixedObject(member, value, Vector4i.class);
                putIVec4(out, at, vector.x, vector.y, vector.z, vector.w);
            }
            case "mat4" -> putMat4(
                    out,
                    at,
                    packProjectionUniform(member.name(), fixedObject(member, value, Matrix4fc.class))
            );
            default -> throw new IllegalStateException(
                    "Iris fixed uniform graph produced unsupported GLSL type '" + member.type()
                            + "' for '" + member.name() + "'"
            );
        }
        return true;
    }

    private static <T> T fixedObject(
            final IrisMetalGlslLinker.UniformMember member,
            final FunctionReturn value,
            final Class<T> expected
    ) {
        if (!expected.isInstance(value.objectReturn)) {
            throw new IllegalStateException(
                    "Iris fixed uniform '" + member.name() + "' (" + member.type() + ") evaluated to "
                            + (value.objectReturn == null ? "null" : value.objectReturn.getClass().getName())
                            + ", expected " + expected.getName()
            );
        }
        return expected.cast(value.objectReturn);
    }

    private static Vector3f uniformVector3(
            final IrisMetalGlslLinker.UniformMember member,
            final Object value,
            final String source
    ) {
        if (value instanceof Vector3f vector) {
            return vector;
        }
        if (value instanceof Vector3d vector) {
            return new Vector3f((float) vector.x, (float) vector.y, (float) vector.z);
        }
        throw new IllegalStateException(
                source + " '" + member.name() + "' (" + member.type() + ") evaluated to "
                        + (value == null ? "null" : value.getClass().getName())
                        + ", expected Vector3f or Vector3d"
        );
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
