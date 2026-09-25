package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalIrisDepthConvention;
import com.mojang.blaze3d.ProjectionType;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rebuilds Mojang's reverse-Z projection as forward zero-to-one while the
 * native Metal Iris path is selected. Unlike Iris's OpenGL adapter, this keeps
 * Metal's required clip range; pack-facing uniforms perform the separate
 * zero-to-one to OpenGL matrix conversion.
 */
@Mixin(Projection.class)
public abstract class ProjectionMetalIrisDepthMixin {
    @Shadow
    private ProjectionType projectionType;

    @Shadow
    private float zNear;

    @Shadow
    private float zFar;

    @Shadow
    private float perspectiveFov;

    @Shadow
    private float width;

    @Shadow
    private float height;

    @Shadow
    private boolean orthoInvertY;

    @Inject(method = "getMatrix", at = @At("RETURN"), cancellable = true)
    private void metallum$useForwardDepth(
            final Matrix4f destination,
            final CallbackInfoReturnable<Matrix4f> cir
    ) {
        if (!MetalIrisDepthConvention.active()) {
            return;
        }
        if (this.projectionType == ProjectionType.PERSPECTIVE) {
            destination.setPerspective(
                    this.perspectiveFov * (float) (Math.PI / 180.0),
                    this.width / this.height,
                    this.zNear,
                    this.zFar,
                    true
            );
        } else {
            destination.setOrtho(
                    0.0F,
                    this.width,
                    this.orthoInvertY ? this.height : 0.0F,
                    this.orthoInvertY ? 0.0F : this.height,
                    this.zNear,
                    this.zFar,
                    true
            );
        }
        cir.setReturnValue(destination);
    }
}
