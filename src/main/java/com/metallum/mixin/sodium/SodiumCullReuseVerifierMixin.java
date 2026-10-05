package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumCullReuseState;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.async.CullTask;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Verifies conservative REGULAR/WIDE cull reuse admissions while leaving
 * Sodium's scheduling and culling behavior untouched.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumCullReuseVerifierMixin {
    @Shadow private CullTask pendingTask;
    @Shadow private boolean cameraChanged;
    @Shadow private boolean needsGraphUpdate;
    @Shadow private DeferredTaskList taskLists;
    @Shadow @Final private ClientLevel level;
    @Shadow @Final private SectionStorage renderSections;
    @Shadow @Final private Map<CullType, SectionTree> cullResults;

    @Unique
    private SodiumCullReuseState metallum$cullReuseState;

    @Unique
    private CullTask metallum$consumeTaskAtHead;

    @Invoker("getSearchDistanceForCullType")
    protected abstract float metallum$invokeSearchDistance(CullType type, FogParameters fogParameters);

    @Unique
    private SodiumCullReuseState metallum$cullReuseState() {
        if (this.metallum$cullReuseState == null) {
            this.metallum$cullReuseState = new SodiumCullReuseState();
        }
        return this.metallum$cullReuseState;
    }

    @Inject(method = "markGraphDirty", at = @At("HEAD"))
    private void metallum$recordCullGraphMutation(CallbackInfo ci) {
        this.metallum$cullReuseState().graphDirty();
    }

    @Inject(method = "scheduleAsyncWork", at = @At("HEAD"))
    private void metallum$evaluateCullReuseCandidate(
            Viewport viewport,
            FogParameters fogParameters,
            boolean useOcclusionCulling,
            CallbackInfo ci
    ) {
        if (this.pendingTask != null) {
            return;
        }

        this.metallum$cullReuseState().beforeSchedule(
                viewport,
                this.metallum$invokeSearchDistance(CullType.REGULAR, fogParameters),
                this.metallum$invokeSearchDistance(CullType.LOCAL, fogParameters),
                useOcclusionCulling,
                this.cameraChanged,
                this.needsGraphUpdate,
                this.cullResults,
                this.renderSections,
                this.level.getMinSectionY(),
                this.level.getMaxSectionY()
        );
    }

    @Inject(method = "consumeCullTaskResults", at = @At("HEAD"))
    private void metallum$captureConsumedCull(boolean waitForCompletion, CallbackInfo ci) {
        this.metallum$consumeTaskAtHead = this.pendingTask;
    }

    @Inject(method = "consumeCullTaskResults", at = @At("TAIL"))
    private void metallum$verifyConsumedCull(boolean waitForCompletion, CallbackInfo ci) {
        CullTask consumed = this.metallum$consumeTaskAtHead;
        this.metallum$consumeTaskAtHead = null;
        if (consumed == null || this.pendingTask != null) {
            return;
        }

        this.metallum$cullReuseState().afterConsume(
                this.cullResults.get(CullType.REGULAR),
                this.cullResults.get(CullType.WIDE),
                this.taskLists,
                this.level.getMinSectionY(),
                this.level.getMaxSectionY()
        );
    }
}
