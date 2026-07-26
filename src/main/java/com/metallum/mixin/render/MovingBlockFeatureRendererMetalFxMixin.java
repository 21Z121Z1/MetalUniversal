package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes the moving-block motion sample current while a falling block's vertices
 * are appended.
 *
 * <p>The entity and item families bracket a private per-submit method, but
 * {@code MovingBlockFeatureRenderer.buildGroup} inlines its per-submit work in the
 * loop body, so there is no such method to hook. The one call that emits the whole
 * submit's geometry is {@code tesselateBlock}, and wrapping it brackets exactly
 * the span during which {@code Group.getVertexBuilder} and {@code getOrAddDraw}
 * run — the two points where a draw is split out and bound to this sample.</p>
 *
 * <p>A redirect is used rather than paired injections because the sample must be
 * cleared even if tesselation throws; a HEAD/RETURN pair around a call cannot
 * express that, and a leaked sample would attach this block's motion to whatever
 * geometry is built next.</p>
 */
@Mixin(MovingBlockFeatureRenderer.class)
public abstract class MovingBlockFeatureRendererMetalFxMixin {
    @Redirect(
            method = "buildGroup",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock("
                            + "Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFF"
                            + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"
            )
    )
    private void metallum$bracketMovingBlockTesselation(
            final ModelBlockRenderer blockRenderer,
            final BlockQuadOutput output,
            final float x,
            final float y,
            final float z,
            final BlockAndTintGetter level,
            final BlockPos pos,
            final BlockState blockState,
            final BlockStateModel model,
            final long seed
    ) {
        // The level argument is the submit's MovingBlockRenderState, which is the
        // key the submit constructor recorded the owner under.
        MetalEntityMotionCapture.beginMovingBlockBuild(level);
        try {
            blockRenderer.tesselateBlock(output, x, y, z, level, pos, blockState, model, seed);
        } finally {
            MetalEntityMotionCapture.endModelBuild();
        }
    }
}
