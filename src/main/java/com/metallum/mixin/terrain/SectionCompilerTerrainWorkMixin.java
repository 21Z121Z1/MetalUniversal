package com.metallum.mixin.terrain;

import com.metallum.client.terrain.VanillaTerrainWorkTelemetry;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerTerrainWorkMixin {
    @ModifyVariable(method = "compile", at = @At("HEAD"), argsOnly = true)
    private RenderSectionRegion metallum$recordBuildStart(final RenderSectionRegion region) {
        VanillaTerrainWorkTelemetry.beginBuild(region);
        return region;
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void metallum$recordBuildEnd(final CallbackInfoReturnable<?> cir) {
        VanillaTerrainWorkTelemetry.endBuild();
    }
}
