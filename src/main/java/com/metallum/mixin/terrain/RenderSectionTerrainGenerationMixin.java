package com.metallum.mixin.terrain;

import com.metallum.client.terrain.VanillaTerrainGenerationRuntime;

import com.metallum.client.terrain.TerrainPublicationGenerationGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
    private SectionMesh metallum$guardPublication(
            final SectionMesh candidate,
            final Operation<SectionMesh> original
    ) {
        // Keep the generation check and vanilla's synchronous pointer exchange in one monitor.
        // Otherwise a content invalidation could land after admission but before publication.
        return VanillaTerrainGenerationRuntime.withPublicationDecision(getSectionNode(), candidate, decision -> {
            if (decision == TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE) {
                // Both callers release the returned old mesh. Return the rejected candidate so
                // vanilla releases it once without publishing it or emitting a publication hook.
                return candidate;
            }
            return original.call(candidate);
        });
    }

    @Inject(method = "releaseSectionMesh", at = @At("HEAD"))
    private void metallum$forgetReleasedGeneration(final SectionMesh mesh, final CallbackInfo ci) {
        VanillaTerrainGenerationRuntime.forgetMesh(mesh);
    }
}
