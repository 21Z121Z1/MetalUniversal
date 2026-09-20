package com.metallum.mixin.terrain;

import java.nio.ByteBuffer;

import com.metallum.client.terrain.VanillaTerrainWorkTelemetry;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionTerrainWorkMixin {
    private SectionRenderDispatcher.RenderSection metallum$self() {
        return (SectionRenderDispatcher.RenderSection)(Object)this;
    }

    @Inject(method = "compileAsync", at = @At("HEAD"))
    private void metallum$recordAsyncAdmission(final RenderSectionRegion region, final CallbackInfo ci) {
        VanillaTerrainWorkTelemetry.beginWork(metallum$self().getSectionNode(), region, true);
    }

    @Inject(method = "compileSync", at = @At("HEAD"))
    private void metallum$recordSyncAdmission(final RenderSectionRegion region, final CallbackInfo ci) {
        VanillaTerrainWorkTelemetry.beginWork(metallum$self().getSectionNode(), region, false);
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void metallum$recordSectionReset(final CallbackInfo ci) {
        VanillaTerrainWorkTelemetry.invalidateSection(
                metallum$self().getSectionNode(),
                "render-section-reset"
        );
    }

    @Inject(method = "addSectionBuffersToUberBuffer", at = @At("RETURN"))
    private void metallum$recordUploadAdmission(
            final ChunkSectionLayer layer,
            final CompiledSectionMesh mesh,
            final ByteBuffer vertexBuffer,
            final ByteBuffer indexBuffer,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        VanillaTerrainWorkTelemetry.bindConstructedMesh(mesh);
        long bytes = 0L;
        if (vertexBuffer != null) {
            bytes += vertexBuffer.remaining();
        }
        if (indexBuffer != null) {
            bytes += indexBuffer.remaining();
        }
        VanillaTerrainWorkTelemetry.uploadQueued(
                mesh,
                bytes,
                "staging-admitted/" + layer
        );
    }

    @Inject(method = "vertexBufferUploadCallback", at = @At("HEAD"))
    private void metallum$recordVertexCopyEncoded(
            final CompiledSectionMesh mesh,
            final ChunkSectionLayer layer,
            final CallbackInfo ci
    ) {
        VanillaTerrainWorkTelemetry.gpuEncoded(mesh, 0L, "vertex-copy-encoded/" + layer);
    }

    @Inject(method = "indexBufferUploadCallback", at = @At("HEAD"))
    private void metallum$recordIndexCopyEncoded(
            final CompiledSectionMesh mesh,
            final ChunkSectionLayer layer,
            final boolean sortedIndexBuffer,
            final CallbackInfo ci
    ) {
        VanillaTerrainWorkTelemetry.gpuEncoded(
                mesh,
                0L,
                (sortedIndexBuffer ? "sorted-index-copy-encoded/" : "index-copy-encoded/") + layer
        );
    }

    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private void metallum$recordPublishedMesh(
            final SectionMesh mesh,
            final CallbackInfoReturnable<SectionMesh> cir
    ) {
        if (mesh != CompiledSectionMesh.EMPTY && mesh != CompiledSectionMesh.UNCOMPILED) {
            // Empty uses a shared sentinel, so it must remain bound to the active build context
            // rather than acquiring an identity association that could leak across sections.
            VanillaTerrainWorkTelemetry.bindConstructedMesh(mesh);
        }
        VanillaTerrainWorkTelemetry.publish(
                metallum$self().getSectionNode(),
                mesh,
                mesh == CompiledSectionMesh.EMPTY ? "empty-mesh-published" : "all-layers-ready"
        );
    }

    @Inject(method = "releaseSectionMesh", at = @At("HEAD"))
    private void metallum$recordRetiredMesh(final SectionMesh oldMesh, final CallbackInfo ci) {
        VanillaTerrainWorkTelemetry.retireMesh(oldMesh, "section-mesh-release");
    }
}
