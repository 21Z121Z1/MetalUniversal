package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.color.ColorProviderRegistry;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import net.caffeinemc.mods.sodium.client.services.PlatformModelEmitter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Predicate;

/**
 * Reuses the two receiver-bound callbacks needed by PlatformModelEmitter.
 *
 * <p>The rewrite is pinned to Sodium 0.9.3 by the mixin plugin. It preserves
 * the renderModel state transition and call order while avoiding two callback
 * allocations per meshed block.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererCachedReferencesMixin extends AbstractBlockRenderContext {
    @Shadow @Final private ColorProviderRegistry colorProviderRegistry;
    @Shadow @Final private Vector3f posOffset;
    @Shadow private ColorProvider<BlockState> colorProvider;
    @Shadow @Final private boolean cutoutLeaves;

    @Unique private Predicate<Direction> metallum$faceCull;
    @Unique private PlatformModelEmitter.Bufferer metallum$defaultBuffer;

    /**
     * @author MetalUniversal
     * @reason cache receiver-bound emitter callbacks for the verified Sodium 0.9.3 target
     */
    @Overwrite
    public void renderModel(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin) {
        this.state = state;
        this.pos = pos;
        this.prepareAoInfo(true);

        this.posOffset.set(origin.getX(), origin.getY(), origin.getZ());
        if (state.hasOffsetFunction()) {
            Vec3 offset = state.getOffset(pos);
            this.posOffset.add((float) offset.x, (float) offset.y, (float) offset.z);
        }

        this.colorProvider = this.colorProviderRegistry.getColorProvider(state.getBlock());
        this.prepareCulling(true);
        this.random.setSeed(state.getSeed(pos));
        this.forceOpaque = ModelBlockRenderer.forceOpaque(this.cutoutLeaves, state);

        Predicate<Direction> faceCull = this.metallum$faceCull;
        PlatformModelEmitter.Bufferer defaultBuffer = this.metallum$defaultBuffer;
        if (faceCull == null || defaultBuffer == null) {
            faceCull = this::isFaceCulled;
            defaultBuffer = this::bufferDefaultModel;
            this.metallum$faceCull = faceCull;
            this.metallum$defaultBuffer = defaultBuffer;
        }

        PlatformModelEmitter.getInstance().emitModel(
                model,
                faceCull,
                this.getForEmitting(),
                this.random,
                this.level,
                pos,
                state,
                defaultBuffer
        );
        this.forceOpaque = false;
    }
}
