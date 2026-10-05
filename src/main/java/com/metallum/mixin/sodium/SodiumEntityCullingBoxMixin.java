package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.sodium.SodiumEntityCullingBoxCache;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.mixin.core.render.world.EntityRendererAccessor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumEntityCullingBoxMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"))
    private <T extends Entity, S extends EntityRenderState> void metallum$clearCullingBox(
            EntityRenderer<T, S> renderer,
            T entity,
            float partialTicks,
            CallbackInfoReturnable<Boolean> cir
    ) {
        SodiumEntityCullingBoxCache.clear();
    }

    @WrapOperation(
            method = "isEntityVisible",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/mixin/core/render/world/EntityRendererAccessor;"
                            + "sodium$getBoundingBoxForCulling(Lnet/minecraft/world/entity/Entity;F)"
                            + "Lnet/minecraft/world/phys/AABB;"
            ),
            remap = false
    )
    private AABB metallum$rememberCullingBox(
            EntityRendererAccessor accessor,
            Entity entity,
            float partialTicks,
            Operation<AABB> original
    ) {
        AABB box = original.call(accessor, entity, partialTicks);
        SodiumEntityCullingBoxCache.put((EntityRenderer<?, ?>) (Object) accessor, entity, partialTicks, box);
        return box;
    }
}
