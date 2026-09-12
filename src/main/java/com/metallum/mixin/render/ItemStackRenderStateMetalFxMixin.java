package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalItemModelIdentityAccess;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors Minecraft 26.2's TrackingItemStackRenderState identity collection for
 * ordinary world item states. The model graph itself emits every identity
 * element through appendModelIdentityElement; animated/special layers remain
 * separately fail-closed for frame interpolation.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMetalFxMixin implements MetalItemModelIdentityAccess {
    @Shadow
    private int activeLayerCount;

    @Shadow
    private ItemStackRenderState.LayerRenderState[] layers;

    @Unique
    private final List<Object> metallum$modelIdentity = new ArrayList<>();

    @Inject(method = "clear", at = @At("HEAD"))
    private void metallum$clearModelIdentity(final CallbackInfo ci) {
        metallum$modelIdentity.clear();
    }

    @Inject(method = "appendModelIdentityElement", at = @At("HEAD"))
    private void metallum$captureModelIdentity(final Object element, final CallbackInfo ci) {
        metallum$modelIdentity.add(element);
    }

    @Override
    public List<Object> metallum$modelIdentity() {
        return List.copyOf(metallum$modelIdentity);
    }

    @Override
    public boolean metallum$hasSpecialLayer() {
        for (int i = 0; i < activeLayerCount; i++) {
            ItemStackLayerRenderStateMetalFxAccessor layer =
                    (ItemStackLayerRenderStateMetalFxAccessor) (Object) layers[i];
            if (layer.metallum$getSpecialRenderer() != null) {
                return true;
            }
        }
        return false;
    }
}
