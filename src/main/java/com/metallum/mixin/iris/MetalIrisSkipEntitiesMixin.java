package com.metallum.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.metal.render.MetalWorldRenderingPipeline;
import net.irisshaders.iris.Iris;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collections;

/** Extends Iris's skipAllRendering entity gate to its native Metal pipeline. */
@Mixin(LevelExtractor.class)
abstract class MetalIrisSkipEntitiesMixin {
    @WrapOperation(
            method = "extractVisibleEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"
            )
    )
    private Iterable<Entity> metallum$skipEntitiesForMetalIris(
            final ClientLevel level,
            final Operation<Iterable<Entity>> original
    ) {
        if (Iris.getPipelineManager().getPipelineNullable()
                instanceof MetalWorldRenderingPipeline pipeline
                && pipeline.shouldSkipAllRendering()) {
            return Collections.emptyList();
        }
        return original.call(level);
    }
}
