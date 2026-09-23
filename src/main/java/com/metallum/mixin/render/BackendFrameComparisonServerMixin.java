package com.metallum.mixin.render;

import com.metallum.client.validation.BackendFrameComparisonClient;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Freezes and configures an opt-in A/B world before its first simulation tick.
 */
@Mixin(IntegratedServer.class)
abstract class BackendFrameComparisonServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void metallum$configureComparisonWorld(
            final BooleanSupplier haveTime,
            final CallbackInfo ci
    ) {
        BackendFrameComparisonClient.configureIntegratedServer(
                (IntegratedServer) (Object) this
        );
        BackendFrameComparisonClient.applyScheduledDimensionSwitch(
                (IntegratedServer) (Object) this
        );
    }
}
