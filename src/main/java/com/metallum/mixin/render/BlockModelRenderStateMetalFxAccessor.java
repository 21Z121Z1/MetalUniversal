package com.metallum.mixin.render;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access used to keep special block-display geometry fail-closed. */
@Mixin(BlockModelRenderState.class)
public interface BlockModelRenderStateMetalFxAccessor {
    @Accessor("specialRenderer")
    SpecialModelRenderer<?> metallum$getSpecialRenderer();
}
