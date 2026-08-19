package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.metallum.client.metal.render.MetalBindingToken;
import com.metallum.client.metal.render.MetalBindingTokenCache;
import com.metallum.client.metal.render.MetalBindingTokenRegistry;
import com.metallum.client.metal.render.MetalCompiledBindingPlan;
import com.metallum.client.metal.render.MetalCompiledBindingPlanProvider;
import com.metallum.client.metal.render.MetalTokenBindingPass;
import com.metallum.client.metal.render.MetalUploadDedupBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps buffer descriptor state stable across repeated Iris/Sodium draw setup.
 *
 * <p>Compatibility-facing calls still arrive with a resource name. The name is
 * compiled once into a process-stable integer token. MetalUniversal-private
 * producers can instead call {@link MetalTokenBindingPass} with a token that
 * was compiled before the draw loop. Once a pipeline is bound, either path
 * resolves to its generation-time dense binding slot and the stable path is an
 * identity probe plus an array lookup.</p>
 *
 * <p>The compatibility name carried by the private surface remains the key for
 * the existing resource maps and diagnostics. Descriptor dirtiness is marked
 * from the token/compiled plan directly, so private callers never resolve that
 * name back through the pipeline resource map.</p>
 *
 * <p>Texture and storage-image calls are not cancelled here because those
 * methods also flush deferred clears or mark potential shader writes. Their
 * redundant native setters are suppressed later by the encoder-local shadow.</p>
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class MetalRenderPassBindingCacheMixin implements MetalTokenBindingPass {
    @Unique
    private static final boolean metallum$TOKENIZED_BINDINGS = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.bindingTokens", "true")
    );
    @Unique
    private static final boolean metallum$COMPILED_BINDING_PLAN = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.compiledBindingPlan", "true")
    );
    @Unique
    private static final BindingState[] metallum$EMPTY_BINDINGS = new BindingState[0];
    @Unique
    private static final MetalBindingToken metallum$GENERATED_IRIS_BLOCK =
            MetalBindingTokenRegistry.resolve("MetallumIrisUniforms");
    @Unique
    private static volatile @Nullable Field metallum$compiledPipelineField;

    @Shadow
    private long dirtyDescriptorMask;

    @Shadow
    public abstract void setUniform(String name, GpuBufferSlice value);

    @Shadow
    public abstract void bindTexture(
            String name,
            @Nullable GpuTextureView textureView,
            @Nullable GpuSampler sampler
    );

    @Shadow
    abstract void bindStorageImage(String name, GpuTextureView textureView);

    @Shadow
    private void markDescriptorDirty(final String name) {
        throw new AssertionError("shadow");
    }

    @Unique
    private final Map<String, BindingState> metallum$legacyUniformBindings =
            new Object2ObjectOpenHashMap<>();
    @Unique
    private final Int2ObjectOpenHashMap<BindingState> metallum$fallbackUniformBindings =
            new Int2ObjectOpenHashMap<>();
    @Unique
    private final Int2ObjectOpenHashMap<BindingState> metallum$storageBindings =
            new Int2ObjectOpenHashMap<>();
    @Unique
    private final MetalBindingTokenCache metallum$tokenCache = new MetalBindingTokenCache();
    @Unique
    private @Nullable MetalCompiledBindingPlan metallum$currentBindingPlan;
    @Unique
    private BindingState[] metallum$uniformBindingsBySlot = metallum$EMPTY_BINDINGS;
    @Unique
    private @Nullable MetalBindingToken metallum$directBindingToken;
    @Unique
    private @Nullable String metallum$directBindingName;

    @Inject(method = "setPipeline", at = @At("RETURN"))
    private void metallum$installCompiledBindingPlan(
            final RenderPipeline pipeline,
            final CallbackInfo ci
    ) {
        if (!metallum$TOKENIZED_BINDINGS || !metallum$COMPILED_BINDING_PLAN) {
            this.metallum$currentBindingPlan = null;
            this.metallum$uniformBindingsBySlot = metallum$EMPTY_BINDINGS;
            return;
        }
        Object compiledPipeline = metallum$compiledPipeline(this);
        MetalCompiledBindingPlan nextPlan = compiledPipeline instanceof MetalCompiledBindingPlanProvider provider
                ? provider.metallum$bindingPlan()
                : null;
        if (nextPlan == this.metallum$currentBindingPlan) {
            return;
        }
        this.metallum$currentBindingPlan = nextPlan;
        this.metallum$uniformBindingsBySlot = nextPlan == null
                ? metallum$EMPTY_BINDINGS
                : new BindingState[nextPlan.bindingCount()];
        // A fallback entry may have been learned before the first pipeline was
        // installed. Drop it so the dense slot becomes the single source of
        // truth for this pipeline generation.
        this.metallum$fallbackUniformBindings.clear();
    }

    @Override
    public void metallum$setUniform(
            final MetalBindingToken token,
            final String compatibilityName,
            final GpuBufferSlice value
    ) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(compatibilityName, "compatibilityName");
        Objects.requireNonNull(value, "value");
        MetalBindingToken previousToken = this.metallum$directBindingToken;
        String previousName = this.metallum$directBindingName;
        this.metallum$directBindingToken = token;
        this.metallum$directBindingName = compatibilityName;
        try {
            this.setUniform(compatibilityName, value);
        } finally {
            this.metallum$directBindingToken = previousToken;
            this.metallum$directBindingName = previousName;
        }
    }

    @Override
    public void metallum$bindTexture(
            final MetalBindingToken token,
            final String compatibilityName,
            @Nullable final GpuTextureView textureView,
            @Nullable final GpuSampler sampler
    ) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(compatibilityName, "compatibilityName");
        MetalBindingToken previousToken = this.metallum$directBindingToken;
        String previousName = this.metallum$directBindingName;
        this.metallum$directBindingToken = token;
        this.metallum$directBindingName = compatibilityName;
        try {
            this.bindTexture(compatibilityName, textureView, sampler);
        } finally {
            this.metallum$directBindingToken = previousToken;
            this.metallum$directBindingName = previousName;
        }
    }

    @Override
    public void metallum$bindStorageImage(
            final MetalBindingToken token,
            final String compatibilityName,
            final GpuTextureView textureView
    ) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(compatibilityName, "compatibilityName");
        Objects.requireNonNull(textureView, "textureView");
        MetalBindingToken previousToken = this.metallum$directBindingToken;
        String previousName = this.metallum$directBindingName;
        this.metallum$directBindingToken = token;
        this.metallum$directBindingName = compatibilityName;
        try {
            this.bindStorageImage(compatibilityName, textureView);
        } finally {
            this.metallum$directBindingToken = previousToken;
            this.metallum$directBindingName = previousName;
        }
    }

    @Inject(
            method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$deduplicateUniform(
            final String name,
            final GpuBufferSlice value,
            final CallbackInfo ci
    ) {
        if (!metallum$TOKENIZED_BINDINGS) {
            this.metallum$deduplicateLegacyUniform(name, value, ci);
            return;
        }

        MetalBindingToken token = this.metallum$directBindingToken;
        if (token == null) {
            token = this.metallum$tokenCache.resolve(name);
        }
        // These Mojang blocks also invalidate the generated Iris draw block;
        // preserve that semantic boundary even when their native binding did
        // not move.
        if (token.invalidatesGeneratedIrisBlock()) {
            return;
        }

        int slot = this.metallum$currentBindingPlan == null
                ? -1
                : this.metallum$currentBindingPlan.slotFor(token);
        BindingState state;
        if (slot >= 0) {
            state = this.metallum$uniformBindingsBySlot[slot];
            if (state == null) {
                state = new BindingState();
                this.metallum$uniformBindingsBySlot[slot] = state;
            } else if (state.matches(value)) {
                IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
                ci.cancel();
                return;
            }
        } else {
            state = this.metallum$fallbackUniformBindings.get(token.id());
            if (state != null && state.matches(value)) {
                IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
                ci.cancel();
                return;
            }
            if (state == null) {
                state = new BindingState();
                this.metallum$fallbackUniformBindings.put(token.id(), state);
            }
        }
        state.update(value);
    }

    @Redirect(
            method = {
                    "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
                    "bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    "bindStorageImage(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/metallum/client/metal/render/MetalRenderPass;markDescriptorDirty(Ljava/lang/String;)V"
            )
    )
    private void metallum$markDescriptorDirtyByToken(
            @Coerce final Object renderPass,
            final String name
    ) {
        MetalBindingToken direct = this.metallum$directBindingToken;
        MetalCompiledBindingPlan plan = this.metallum$currentBindingPlan;
        if (direct != null && plan != null) {
            String directName = this.metallum$directBindingName;
            if (name == directName || name.equals(directName)) {
                this.metallum$markDescriptorDirty(plan, direct);
                return;
            }
            if (direct.invalidatesGeneratedIrisBlock() && "MetallumIrisUniforms".equals(name)) {
                this.metallum$markDescriptorDirty(plan, metallum$GENERATED_IRIS_BLOCK);
                return;
            }
        }
        this.markDescriptorDirty(name);
    }

    @Unique
    private void metallum$markDescriptorDirty(
            final MetalCompiledBindingPlan plan,
            final MetalBindingToken token
    ) {
        int slot = plan.slotFor(token);
        if (slot >= 0) {
            this.dirtyDescriptorMask |= 1L << plan.physicalBindingIndex(slot);
        }
    }

    @Unique
    private void metallum$deduplicateLegacyUniform(
            final String name,
            final GpuBufferSlice value,
            final CallbackInfo ci
    ) {
        if ("DynamicTransforms".equals(name) || "Projection".equals(name)) {
            return;
        }
        BindingState state = this.metallum$legacyUniformBindings.get(name);
        if (state != null && state.matches(value)) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        if (state == null) {
            state = new BindingState();
            this.metallum$legacyUniformBindings.put(name, state);
        }
        state.update(value);
    }

    @Inject(
            method = "bindStorageBuffer(ILcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$deduplicateStorageBuffer(
            final int binding,
            final GpuBufferSlice value,
            final CallbackInfo ci
    ) {
        BindingState state = this.metallum$storageBindings.get(binding);
        if (state != null && state.matches(value)) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        if (state == null) {
            state = new BindingState();
            this.metallum$storageBindings.put(binding, state);
        }
        state.update(value);
    }

    @Unique
    private static Object metallum$compiledPipeline(final Object renderPass) {
        Field field = metallum$compiledPipelineField;
        if (field == null) {
            synchronized (renderPass.getClass()) {
                field = metallum$compiledPipelineField;
                if (field == null) {
                    try {
                        field = renderPass.getClass().getDeclaredField("compiledPipeline");
                        field.setAccessible(true);
                        metallum$compiledPipelineField = field;
                    } catch (ReflectiveOperationException exception) {
                        throw new IllegalStateException(
                                "Unable to resolve MetalRenderPass.compiledPipeline",
                                exception
                        );
                    }
                }
            }
        }
        try {
            return field.get(renderPass);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read MetalRenderPass.compiledPipeline", exception);
        }
    }

    @Unique
    private static long metallum$bindingVersion(final GpuBuffer buffer) {
        return buffer instanceof MetalUploadDedupBuffer versioned
                ? versioned.metallum$bindingVersion()
                : 0L;
    }

    private static final class BindingState {
        private GpuBuffer buffer;
        private long backingVersion;
        private long offset;
        private long length;

        private boolean matches(final GpuBufferSlice value) {
            return this.buffer == value.buffer()
                    && this.backingVersion == metallum$bindingVersion(value.buffer())
                    && this.offset == value.offset()
                    && this.length == value.length();
        }

        private void update(final GpuBufferSlice value) {
            this.buffer = value.buffer();
            this.backingVersion = metallum$bindingVersion(value.buffer());
            this.offset = value.offset();
            this.length = value.length();
        }
    }
}
