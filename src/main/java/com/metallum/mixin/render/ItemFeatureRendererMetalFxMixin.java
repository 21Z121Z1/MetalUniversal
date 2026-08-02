package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the item geometry produced for one submit attributable to its entity,
 * mirroring {@link ModelFeatureRendererMetalFxMixin} for the {@code core/item}
 * pipeline family. Both the main and the foil pass run through
 * {@code prepareSubmit}, so the owner lookup must not consume the submit.
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMetalFxMixin {
    @Inject(method = "prepareSubmit", at = @At("HEAD"))
    private void metallum$beginMotionItem(
            final ItemFeatureRenderer.Submit submit,
            final boolean foil,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.beginItemBuild(submit);
    }

    @Inject(method = "prepareSubmit", at = @At("RETURN"))
    private void metallum$endMotionItem(
            final ItemFeatureRenderer.Submit submit,
            final boolean foil,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
