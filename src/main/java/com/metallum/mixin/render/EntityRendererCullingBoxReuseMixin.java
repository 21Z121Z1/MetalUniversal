package com.metallum.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.sodium.SodiumEntityCullingBoxCache;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererCullingBoxReuseMixin {
    @WrapOperation(
            method = "shouldRender",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;"
                            + "getBoundingBoxForCulling(Lnet/minecraft/world/entity/Entity;F)"
                            + "Lnet/minecraft/world/phys/AABB;"
            )
    )
    private AABB metallum$reuseCullingBox(
            EntityRenderer<?, ?> renderer,
            Entity entity,
            float partialTicks,
            Operation<AABB> original
    ) {
        AABB cached = SodiumEntityCullingBoxCache.take(renderer, entity, partialTicks);
        return cached != null ? cached : original.call(renderer, entity, partialTicks);
    }
}
