package com.metallum.mixin.terrain;

import java.nio.ByteBuffer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
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

    @WrapOperation(
            method = "addSectionBuffersToUberBuffer",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/UberGpuBuffer;addAllocation("
                    + "Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/UberGpuBuffer$UploadCallback;Ljava/nio/ByteBuffer;)Z")
    )
    private boolean metallum$recordUploadAdmission(
            final UberGpuBuffer<?> buffer,
            final Object mesh,
            final UberGpuBuffer.UploadCallback<?> callback,
            final ByteBuffer data,
            final Operation<Boolean> original,
            @Local(argsOnly = true) final ChunkSectionLayer layer
    ) {
        long bytes = data.remaining();
        boolean admitted = original.call(buffer, mesh, callback, data);
        if (admitted) {
            // Still under vanilla's copyLock, before the render thread can consume
            // this allocation. RETURN runs after unlock and can trail GPU_ENCODED.
            // Record each success even when the other buffer needs a staging retry.
            VanillaTerrainWorkTelemetry.bindConstructedMesh(mesh);
            VanillaTerrainWorkTelemetry.uploadQueued(mesh, bytes, "staging-admitted/" + layer);
        }
        return admitted;
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
        if (mesh == CompiledSectionMesh.EMPTY) {
            VanillaTerrainWorkTelemetry.publishEmpty(metallum$self().getSectionNode(), "empty-mesh-published");
            return;
        }
        if (mesh == CompiledSectionMesh.UNCOMPILED) {
            return;
        }
        // Block-entity-only results publish directly without a staging callback.
        VanillaTerrainWorkTelemetry.bindConstructedMesh(mesh);
        VanillaTerrainWorkTelemetry.publish(
                metallum$self().getSectionNode(),
                mesh,
                "all-layers-ready"
        );
    }

    @Inject(method = "releaseSectionMesh", at = @At("HEAD"))
    private void metallum$recordRetiredMesh(final SectionMesh oldMesh, final CallbackInfo ci) {
        VanillaTerrainWorkTelemetry.retireMesh(oldMesh, "section-mesh-release");
    }
}
