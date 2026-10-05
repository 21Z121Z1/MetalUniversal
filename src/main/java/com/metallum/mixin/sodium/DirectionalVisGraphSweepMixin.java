package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumPerformanceOptions;
import com.metallum.client.sodium.SodiumVisibilitySweep;
import com.metallum.client.sodium.SodiumVisibilitySweepTelemetry;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.DirectionalVisGraph;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.VisibilityEncoding;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DirectionalVisGraph.class, remap = false)
public abstract class DirectionalVisGraphSweepMixin {
    @Unique private final int[] metallum$opaqueRows = new int[256];
    @Unique private final int[] metallum$sweepScratch = new int[256];
    @Unique private int metallum$filled;

    @Inject(method = "setOpaque", at = @At("TAIL"))
    private void metallum$trackOpaque(int x, int y, int z, CallbackInfo ci) {
        this.metallum$opaqueRows[(y << 4) | z] |= 1 << x;
        this.metallum$filled++;
    }

    @Inject(method = "resolve", at = @At("HEAD"), cancellable = true)
    private void metallum$accelerate(CallbackInfoReturnable<VisibilitySet[]> cir) {
        if (!SodiumPerformanceOptions.visibilitySweepFastEnabled()) {
            return;
        }
        VisibilitySet[] candidate = SodiumVisibilitySweep.resolve(
                this.metallum$opaqueRows,
                this.metallum$filled,
                this.metallum$sweepScratch
        );
        SodiumVisibilitySweepTelemetry.recordAccelerated();
        cir.setReturnValue(candidate);
    }

    @Inject(method = "resolve", at = @At("RETURN"))
    private void metallum$verify(CallbackInfoReturnable<VisibilitySet[]> cir) {
        if (!SodiumPerformanceOptions.visibilitySweepVerifyEnabled()) {
            return;
        }

        VisibilitySet[] expected = cir.getReturnValue();
        VisibilitySet[] candidate = SodiumVisibilitySweep.resolve(
                this.metallum$opaqueRows,
                this.metallum$filled,
                this.metallum$sweepScratch
        );
        boolean match = sameVisibility(expected, candidate);
        SodiumVisibilitySweepTelemetry.recordVerified(match);
        if (!match) {
            throw new IllegalStateException("Sodium visibility sweep diverged from 0.9.3 DirectionalVisGraph");
        }
    }

    @Unique
    private static boolean sameVisibility(VisibilitySet[] expected, VisibilitySet[] candidate) {
        if (expected == null || candidate == null || expected.length != candidate.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (VisibilityEncoding.encode(expected[i]) != VisibilityEncoding.encode(candidate[i])) {
                return false;
            }
        }
        return true;
    }
}
