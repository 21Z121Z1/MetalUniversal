package com.metallum.mixin.render;

import com.metallum.client.ClientPerformanceOptions;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ModelPart.class)
public abstract class ModelPartIndexedCompileMixin {
    @Shadow @Final private List<ModelPart.Cube> cubes;

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void metallum$compileIndexed(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int packedLight,
            int packedOverlay,
            int color,
            CallbackInfo ci
    ) {
        if (!ClientPerformanceOptions.modelPartIndexedLoopEnabled()) return;
        List<ModelPart.Cube> local = this.cubes;
        for (int index = 0, size = local.size(); index < size; index++) {
            local.get(index).compile(pose, consumer, packedLight, packedOverlay, color);
        }
        ci.cancel();
    }
}
