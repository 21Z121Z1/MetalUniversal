package com.metallum.mixin.startup;

import com.metallum.client.startup.StartupPerformance;
import net.minecraft.CrashReport;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Main.class)
public abstract class CrashReportPreloadMixin {
    @Redirect(method = "main", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/CrashReport;preload()V"
    ))
    private static void metallum$preloadCrashReport() {
        if (StartupPerformance.asyncCrashPreloadEnabled()) {
            StartupPerformance.preloadCrashReportAsync();
        } else {
            CrashReport.preload();
        }
    }
}
