package com.metallum.mixin.render;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access used to reject item layers that bypass ordinary staged item geometry. */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemStackLayerRenderStateMetalFxAccessor {
    @Accessor("specialRenderer")
    SpecialModelRenderer<Object> metallum$getSpecialRenderer();
}
