package com.metallum.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.metallum.client.metal.render.MetalMotionHooks;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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
 * <p>The call is wrapped rather than bracketed with paired injections because the
 * sample must be cleared even if tesselation throws; a HEAD/RETURN pair around a
 * call cannot express that, and a leaked sample would attach this block's motion
 * to whatever geometry is built next.</p>
 *
 * <p>It is a MixinExtras {@code @WrapOperation} and not a {@code @Redirect}
 * because {@code fabric-renderer-api-v1} redirects this same {@code tesselateBlock}
 * invoke (its {@code MovingBlockFeatureRendererMixin}). {@code @Redirect} is
 * exclusive: whichever mod wins, the other is skipped and then fails its own
 * injection check, which aborts game init outright. {@code @WrapOperation} is
 * designed to compose, so both wrappers apply.</p>
 */
@Mixin(MovingBlockFeatureRenderer.class)
public abstract class MovingBlockFeatureRendererMetalFxMixin {
    @WrapOperation(
            method = MetalMotionHooks.BUILD_GROUP_METHOD,
            at = @At(value = "INVOKE", target = MetalMotionHooks.TESSELATE_BLOCK_TARGET)
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
            final long seed,
            final Operation<Void> original
    ) {
        // The level argument is the submit's MovingBlockRenderState, which is the
        // key the submit constructor recorded the owner under.
        MetalEntityMotionCapture.beginMovingBlockBuild(level);
        try {
            original.call(blockRenderer, output, x, y, z, level, pos, blockState, model, seed);
        } finally {
            MetalEntityMotionCapture.endModelBuild();
        }
    }
}
