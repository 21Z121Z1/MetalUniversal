package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class RenderTypeFeatureGroupMetalFxMixin {
    @Shadow
    @Final
    private StagedVertexBuffer stagedBuffer;

    @Shadow
    @Final
    private List<StagedVertexBuffer.Draw> draws;

    @Shadow
    @Final
    private List<PreparedRenderType> drawRenderTypes;

    @Shadow
    private StagedVertexBuffer.Draw lastDraw;

    @Shadow
    private RenderType lastRenderType;

    @Inject(method = "getVertexBuilder", at = @At("HEAD"))
    private void metallum$preventCrossEntityConsolidation(
            final RenderType renderType,
            final CallbackInfoReturnable<VertexConsumer> cir
    ) {
        if (MetalEntityMotionCapture.shouldSplitEntityDraw(renderType.pipeline())) {
            this.lastDraw = null;
            this.lastRenderType = null;
        }
    }

    @Inject(method = "getOrAddDraw", at = @At("HEAD"), cancellable = true)
    private void metallum$appendEntityOwnedDraw(
            final RenderType renderType,
            final CallbackInfoReturnable<StagedVertexBuffer.Draw> cir
    ) {
        if (!MetalEntityMotionCapture.shouldSplitEntityDraw(renderType.pipeline())) {
            return;
        }
        StagedVertexBuffer.Draw draw = this.stagedBuffer.appendDraw(
                renderType.format(),
                renderType.primitiveTopology(),
                renderType.sortOnUpload()
                        ? com.mojang.blaze3d.systems.RenderSystem.getProjectionType().vertexSorting()
                        : null
        );
        this.draws.add(draw);
        this.drawRenderTypes.add(renderType.prepare());
        MetalEntityMotionCapture.attachDraw(draw);
        cir.setReturnValue(draw);
    }
}
