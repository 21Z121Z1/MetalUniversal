package com.metallum.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.metal.render.MetalShaderLibraryLifetime;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Configs owns includes before apply as well as while its cache is current. */
@Mixin(targets="net.minecraft.client.renderer.ShaderManager$Configs")
public abstract class ShaderConfigsMetalLifetimeMixin implements MetalShaderLibraryLifetime.Provider {
    @Unique private MetalShaderLibraryLifetime metallum$lifetime;

    @Inject(method="<init>(Ljava/util/Map;Ljava/util/Map;Ljava/util/Map;)V",at=@At("RETURN"))
    private void metallum$ownLoadedIncludes(CallbackInfo ci) {
        metallum$lifetime=new MetalShaderLibraryLifetime((ShaderSource)(Object)this);
    }

    @Override public MetalShaderLibraryLifetime metallum$shaderLibraryLifetime() {return metallum$lifetime;}

    @WrapMethod(method="close")
    private void metallum$retireAfterCompilerTasks(Operation<Void> original) {
        metallum$lifetime.close(()->original.call());
    }
}
