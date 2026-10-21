package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalCloudMotion;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.ByteBuffer;

/** Reads actual procedural face bytes; ordinary and OIT share the real draw boundary. */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMetalFxMixin {
    @Shadow private boolean needsRebuild;
    @Shadow private CloudRenderer.@Nullable TextureData texture;
    @Shadow private @Nullable MappableRingBuffer utb;
    @Shadow private int prevCellX;
    @Shadow private int prevCellZ;
    @Unique private boolean metallum$capturingMesh;

    @Inject(method="prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",at=@At("HEAD"))
    private void metallum$captureOnEnable(CallbackInfo ci) {
        if(MetalCloudMotion.collecting() && !MetalCloudMotion.hasFaces(this))needsRebuild=true;
    }

    @Inject(method="prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",at=@At("RETURN"))
    private void metallum$prepared(int color,CloudStatus status,float bottom,int range,Vec3 camera,long time,float partial,CallbackInfo ci) {
        MetalCloudMotion.prepared(this,camera.x,camera.z,time,partial,texture==null ? 0 : texture.width(),
                texture==null ? 0 : texture.height(),prevCellX,prevCellZ);
    }

    @Inject(method="buildMesh",at=@At("HEAD"))
    private void metallum$beginMesh(CallbackInfo ci) {
        MetalCloudMotion.beginMesh(this);
        metallum$capturingMesh=MetalCloudMotion.collecting();
    }

    @Inject(method="buildMesh",at=@At("RETURN"))
    private void metallum$endMesh(CallbackInfo ci,@Local(argsOnly=true) ByteBuffer bytes) {
        if(metallum$capturingMesh) {
            MetalCloudMotion.facesFromMesh(this,bytes,utb==null ? null : utb.currentBuffer());
            metallum$capturingMesh=false;
        }
    }

    @WrapOperation(method="render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/RenderPass;drawIndexed(IIIII)V"))
    private void metallum$draw(RenderPass pass,int count,int instances,int first,int base,int instance,Operation<Void> original) {
        original.call(pass,count,instances,first,base,instance);
        MetalCloudMotion.afterDraw(this,pass,count,instances,first,base,instance);
    }

    @Inject(method="apply(Ljava/util/Optional;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",at=@At("HEAD"))
    private void metallum$reloaded(CallbackInfo ci) {MetalCloudMotion.reloaded(this);metallum$capturingMesh=false;}

    @Inject(method="close",at=@At("RETURN"))
    private void metallum$close(CallbackInfo ci) {MetalCloudMotion.closeRenderer(this);metallum$capturingMesh=false;}
}
