package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalWeatherMotion;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Map;

/** Captures actual weather inputs and hooks the indexed execution shared by ordinary and OIT. */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMetalFxMixin {
    @Shadow @Final private float[] columnSizeX;
    @Shadow @Final private float[] columnSizeZ;
    @Shadow private @Nullable AbstractTexture rainTexture;
    @Shadow private @Nullable AbstractTexture snowTexture;
    @Unique private final Map<WeatherEffectRenderer.ColumnInstance,MetalWeatherMotion.Rates> metallum$rates=new IdentityHashMap<>();
    @Unique private @Nullable WeatherRenderState metallum$preparing;
    @Unique private double metallum$rainRate=Double.NaN;
    @Unique private double metallum$snowU=Double.NaN;
    @Unique private double metallum$snowV=Double.NaN;

    @Inject(method="<init>",at=@At("RETURN"))
    private void metallum$finiteCameraColumn(CallbackInfo ci) {MetalWeatherMotion.repairZeroRadiusColumn(columnSizeX,columnSizeZ);}

    @Inject(method="createRainColumnInstance",at=@At("HEAD"))
    private void metallum$beginRain(RandomSource random,long ticks,int x,int bottom,int top,int z,int light,float partial,
            CallbackInfoReturnable<WeatherEffectRenderer.ColumnInstance> cir) {metallum$rainRate=Double.NaN;}

    @ModifyExpressionValue(method="createRainColumnInstance",at=@At(value="INVOKE",target="Lnet/minecraft/util/RandomSource;nextFloat()F"))
    private float metallum$rainSpeed(float sampled) {
        if(MetalWeatherMotion.collecting())metallum$rainRate=-(3.0F+sampled)/32.0F;
        return sampled;
    }

    @Inject(method="createRainColumnInstance",at=@At("RETURN"))
    private void metallum$rainColumn(RandomSource random,long ticks,int x,int bottom,int top,int z,int light,float partial,
            CallbackInfoReturnable<WeatherEffectRenderer.ColumnInstance> cir) {
        if(MetalWeatherMotion.collecting())metallum$rates.put(cir.getReturnValue(),
                new MetalWeatherMotion.Rates(ticks+(double)partial,Math.floorDiv(ticks,131072L),0,metallum$rainRate));
    }

    @Inject(method="createSnowColumnInstance",at=@At("HEAD"))
    private void metallum$beginSnow(RandomSource random,long ticks,int x,int bottom,int top,int z,int light,float partial,
            CallbackInfoReturnable<WeatherEffectRenderer.ColumnInstance> cir) {metallum$snowU=metallum$snowV=Double.NaN;}

    @ModifyExpressionValue(method="createSnowColumnInstance",at=@At(value="INVOKE",target="Lnet/minecraft/util/RandomSource;nextGaussian()D",ordinal=0))
    private double metallum$snowU(double sampled) {
        if(MetalWeatherMotion.collecting())metallum$snowU=.01F*(float)sampled;return sampled;
    }

    @ModifyExpressionValue(method="createSnowColumnInstance",at=@At(value="INVOKE",target="Lnet/minecraft/util/RandomSource;nextGaussian()D",ordinal=1))
    private double metallum$snowV(double sampled) {
        if(MetalWeatherMotion.collecting())metallum$snowV=.001F*(float)sampled-1.0/512;return sampled;
    }

    @Inject(method="createSnowColumnInstance",at=@At("RETURN"))
    private void metallum$snowColumn(RandomSource random,long ticks,int x,int bottom,int top,int z,int light,float partial,
            CallbackInfoReturnable<WeatherEffectRenderer.ColumnInstance> cir) {
        if(MetalWeatherMotion.collecting())metallum$rates.put(cir.getReturnValue(),
                new MetalWeatherMotion.Rates(ticks+(double)partial,Math.floorDiv(ticks,131072L),metallum$snowU,metallum$snowV));
    }

    @WrapMethod(method="prepare")
    private void metallum$prepare(Vec3 camera,WeatherRenderState state,Operation<Void> original) {
        metallum$preparing=state;
        try {original.call(camera,state);} finally {metallum$preparing=null;metallum$rates.clear();}
    }

    @WrapMethod(method="uploadVertexBuffer")
    private void metallum$upload(ByteBuffer vertices,Operation<Void> original) {
        ByteBuffer source=vertices.duplicate().order(ByteOrder.nativeOrder());
        original.call(vertices);
        if(metallum$preparing!=null)MetalWeatherMotion.prepare(this,metallum$preparing,source,metallum$rates);
    }

    @WrapOperation(method="renderWeather",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/RenderPass;drawIndexed(IIIII)V"))
    private void metallum$draw(RenderPass pass,int count,int instances,int firstIndex,int baseVertex,int firstInstance,
            Operation<Void> original,@Local(argsOnly=true) AbstractTexture texture) {
        original.call(pass,count,instances,firstIndex,baseVertex,firstInstance);
        if(texture==rainTexture)MetalWeatherMotion.afterDraw(this,MetalWeatherMotion.Kind.RAIN,pass,count,instances,firstIndex,baseVertex,firstInstance);
        else if(texture==snowTexture)MetalWeatherMotion.afterDraw(this,MetalWeatherMotion.Kind.SNOW,pass,count,instances,firstIndex,baseVertex,firstInstance);
    }

    @Inject(method="close",at=@At("RETURN"))
    private void metallum$close(CallbackInfo ci) {
        metallum$rates.clear();metallum$preparing=null;MetalWeatherMotion.closeRenderer(this);
    }
}
