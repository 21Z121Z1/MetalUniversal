package com.metallum.e2e.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.e2e.StationaryTaskActivity;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Test-only lifecycle accounting; it does not alter Vanilla task scheduling. */
@Mixin(SectionRenderDispatcher.class)
abstract class StationaryDispatcherActivityMixin implements StationaryDispatcherActivityAccess {
    @Unique
    private final StationaryTaskActivity metallum$taskActivity = new StationaryTaskActivity();

    @WrapMethod(method = "runTask")
    private void metallum$trackTaskInvocation(Operation<Void> original) {
        metallum$taskActivity.run(() -> original.call());
    }

    @Override
    public int metallum$activeTaskInvocations() {
        return metallum$taskActivity.activeInvocations();
    }
}
