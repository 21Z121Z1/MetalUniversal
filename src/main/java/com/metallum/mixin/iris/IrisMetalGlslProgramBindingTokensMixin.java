package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalBindingNameProvider;
import com.metallum.client.metal.render.MetalBindingToken;
import com.metallum.client.metal.render.MetalBindingTokenRegistry;
import com.metallum.client.metal.render.MetalIrisBindingTokenLayout;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Compiles one immutable token sequence when an Iris raster program is constructed. */
@Mixin(targets = "com.metallum.client.metal.render.MetalIrisShaderCompiler$GlslProgram")
public abstract class IrisMetalGlslProgramBindingTokensMixin implements MetalIrisBindingTokenLayout {
    @Shadow
    @Final
    private List<String> uniformBlockNames;

    @Shadow
    @Final
    private List<?> samplers;

    @Unique
    private String[] metallum$uniformBindingNames;
    @Unique
    private MetalBindingToken[] metallum$uniformBindingTokens;
    @Unique
    private String[] metallum$samplerBindingNames;
    @Unique
    private MetalBindingToken[] metallum$samplerBindingTokens;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$compileBindingTokens(final CallbackInfo ci) {
        this.metallum$uniformBindingNames = this.uniformBlockNames.toArray(String[]::new);
        this.metallum$uniformBindingTokens = new MetalBindingToken[this.metallum$uniformBindingNames.length];
        for (int index = 0; index < this.metallum$uniformBindingNames.length; index++) {
            this.metallum$uniformBindingTokens[index] =
                    MetalBindingTokenRegistry.resolve(this.metallum$uniformBindingNames[index]);
        }

        this.metallum$samplerBindingNames = new String[this.samplers.size()];
        this.metallum$samplerBindingTokens = new MetalBindingToken[this.samplers.size()];
        for (int index = 0; index < this.samplers.size(); index++) {
            Object sampler = this.samplers.get(index);
            if (!(sampler instanceof MetalBindingNameProvider named)) {
                throw new IllegalStateException(
                        "Iris sampler record is missing the Metal binding-name accessor: "
                                + sampler.getClass().getName()
                );
            }
            String name = named.metallum$bindingName();
            this.metallum$samplerBindingNames[index] = name;
            this.metallum$samplerBindingTokens[index] = MetalBindingTokenRegistry.resolve(name);
        }
    }

    @Override
    public int metallum$uniformBindingCount() {
        return this.metallum$uniformBindingTokens.length;
    }

    @Override
    public MetalBindingToken metallum$uniformBindingToken(final int index) {
        return this.metallum$uniformBindingTokens[index];
    }

    @Override
    public String metallum$uniformBindingName(final int index) {
        return this.metallum$uniformBindingNames[index];
    }

    @Override
    public int metallum$samplerBindingCount() {
        return this.metallum$samplerBindingTokens.length;
    }

    @Override
    public MetalBindingToken metallum$samplerBindingToken(final int index) {
        return this.metallum$samplerBindingTokens[index];
    }

    @Override
    public String metallum$samplerBindingName(final int index) {
        return this.metallum$samplerBindingNames[index];
    }
}
