package com.metallum.mixin.terrain;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.terrain.VanillaTerrainGenerationRuntime;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * T1b ownership bridge around vanilla RenderSection. The original setSectionMesh remains the only
 * publisher; stale candidates are returned as their own "old mesh" so both vanilla call sites take
 * their existing release path without touching the currently displayed mesh.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionTerrainGenerationMixin {
    @Shadow
    public abstract long getSectionNode();

    @Inject(method = "createCompileTask", at = @At("RETURN"))
    private void metallum$captureCompileGeneration(
            final RenderSectionRegion region,
            final CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> cir
    ) {
        SectionRenderDispatcher.RenderSection.SectionTask task = cir.getReturnValue();
        if (task != null) {
            VanillaTerrainGenerationRuntime.registerTask(task, getSectionNode());
        }
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void metallum$invalidateSectionLifetime(final CallbackInfo ci) {
        VanillaTerrainGenerationRuntime.invalidateSectionLifetime(getSectionNode());
    }

    @Inject(method = "addSectionBuffersToUberBuffer", at = @At("HEAD"))
    private void metallum$bindStagedMeshGeneration(
            final ChunkSectionLayer layer,
            final CompiledSectionMesh mesh,
            final ByteBuffer vertexBuffer,
            final ByteBuffer indexBuffer,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        VanillaTerrainGenerationRuntime.bindMeshFromActiveTask(mesh);
    }

    @WrapMethod(method = "setSectionMesh")
    private SectionMesh metallum$guardPublication(final SectionMesh candidate, final Operation<SectionMesh> original) {
        return VanillaTerrainGenerationRuntime.publish(getSectionNode(), candidate, () -> original.call(candidate));
    }

    @Inject(method = "releaseSectionMesh", at = @At("HEAD"))
    private void metallum$forgetReleasedGeneration(final SectionMesh mesh, final CallbackInfo ci) {
        VanillaTerrainGenerationRuntime.forgetMesh(mesh);
    }
}
