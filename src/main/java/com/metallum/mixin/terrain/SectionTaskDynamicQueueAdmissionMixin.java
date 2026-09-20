package com.metallum.mixin.terrain;

import com.metallum.client.terrain.BoundedTerrainTaskAdmission;
import com.metallum.client.terrain.VanillaTerrainAdmissionTelemetry;
import java.util.List;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Opt-in T1a admission around vanilla's existing SectionTaskDynamicQueue.
 *
 * <p>The original queue remains the scheduling authority: its distance search and
 * MAX_RECOMPILE_QUOTA are untouched. This mixin only removes already-terminal entries earlier,
 * bounds newly admitted live work, and keeps overflow in an insertion-ordered deferred layer.
 * If that layer cannot preserve semantics within its configured bound, every owned task is
 * returned to vanilla and the experiment fails open until the queue is cleared.</p>
 */
@Mixin(SectionTaskDynamicQueue.class)
abstract class SectionTaskDynamicQueueAdmissionMixin {
    @Shadow
    @Final
    private List<SectionRenderDispatcher.RenderSection.SectionTask> tasks;

    @Unique
    private BoundedTerrainTaskAdmission<SectionRenderDispatcher.RenderSection.SectionTask>
            metallum$terrainAdmission;

    @Unique
    private static final BoundedTerrainTaskAdmission.TaskOps<
            SectionRenderDispatcher.RenderSection.SectionTask> METALLUM_TASK_OPS =
            new BoundedTerrainTaskAdmission.TaskOps<>() {
                @Override
                public Object ownerIdentity(final SectionRenderDispatcher.RenderSection.SectionTask task) {
                    // Freeze the section coordinate at admission time. RenderSection itself is a
                    // recycled view-area slot and its MutableBlockPos changes when the camera moves;
                    // using that mutable object's identity would transfer fairness debt across
                    // unrelated sections.
                    return SectionPos.asLong(task.getRenderOrigin());
                }

                @Override
                public Object kindIdentity(final SectionRenderDispatcher.RenderSection.SectionTask task) {
                    // Compile and transparency-resort tasks for one section are different semantic
                    // slots and must never be coalesced together.
                    return task.getClass();
                }

                @Override
                public boolean isTerminal(final SectionRenderDispatcher.RenderSection.SectionTask task) {
                    SectionTaskTerrainAdmissionAccessor accessor =
                            (SectionTaskTerrainAdmissionAccessor)(Object)task;
                    return accessor.metallum$isCancelled().get() || accessor.metallum$isCompleted().get();
                }

                @Override
                public void cancel(final SectionRenderDispatcher.RenderSection.SectionTask task) {
                    task.cancel();
                }
            };

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void metallum$admitOrDefer(
            final SectionRenderDispatcher.RenderSection.SectionTask task,
            final CallbackInfo ci
    ) {
        BoundedTerrainTaskAdmission<SectionRenderDispatcher.RenderSection.SectionTask> admission =
                metallum$admission();

        if (admission.active() && tasks.size() >= admission.queueCapacity()) {
            admission.recordCompactedVanillaTasks(metallum$compactTerminalVanillaTasks());
        }

        BoundedTerrainTaskAdmission.OfferResult<SectionRenderDispatcher.RenderSection.SectionTask> result =
                admission.offer(task, tasks.size(), System.nanoTime());
        int vanillaQueuedAfter = tasks.size();
        switch (result.action()) {
            case DISCARD_TERMINAL, DEFER, REPLACE_DEFERRED -> ci.cancel();
            case FAIL_OPEN -> {
                // Restore all work held by the experimental layer before allowing vanilla's
                // original add() to append the current task.
                tasks.addAll(result.failOpenTasks());
                vanillaQueuedAfter = tasks.size() + 1;
            }
            case BASELINE, ADMIT -> {
                // Vanilla add() executes after this HEAD injection.
                vanillaQueuedAfter = tasks.size() + 1;
            }
        }
        VanillaTerrainAdmissionTelemetry.publish(admission, vanillaQueuedAfter, System.nanoTime());
    }

    @Inject(method = "poll", at = @At("HEAD"))
    private void metallum$refillVanillaQueue(
            final Vec3 cameraPos,
            final CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> cir
    ) {
        BoundedTerrainTaskAdmission<SectionRenderDispatcher.RenderSection.SectionTask> admission =
                metallum$admission();
        if (!admission.active() || admission.deferredSize() == 0) {
            return;
        }

        admission.recordCompactedVanillaTasks(metallum$compactTerminalVanillaTasks());
        if (!tasks.isEmpty()) {
            // Do not continuously refill a partially drained batch: vanilla's nearest-distance
            // selection could then starve one old far task indefinitely. Refill only at the cohort
            // boundary, while preserving vanilla's own ordering inside each admitted batch.
            return;
        }

        BoundedTerrainTaskAdmission.DrainResult<SectionRenderDispatcher.RenderSection.SectionTask> ready =
                admission.drain(admission.queueCapacity(), System.nanoTime());
        // Direct insertion is intentional: poll() already runs under the queue monitor and vanilla
        // immediately applies its own distance + recompile-quota choice across this bounded batch.
        tasks.addAll(ready.tasks());
        VanillaTerrainAdmissionTelemetry.publish(admission, tasks.size(), System.nanoTime());
    }

    @Inject(method = "poll", at = @At("RETURN"), cancellable = true)
    private void metallum$recoverCancelledCohortRace(
            final Vec3 cameraPos,
            final CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> cir
    ) {
        if (cir.getReturnValue() != null) {
            return;
        }
        BoundedTerrainTaskAdmission<SectionRenderDispatcher.RenderSection.SectionTask> admission =
                metallum$terrainAdmission;
        if (admission == null || !admission.active() || admission.deferredSize() == 0) {
            return;
        }

        admission.recordCompactedVanillaTasks(metallum$compactTerminalVanillaTasks());
        if (!tasks.isEmpty()) {
            return;
        }
        var ready = admission.drain(admission.queueCapacity(), System.nanoTime());
        if (ready.tasks().isEmpty()) {
            VanillaTerrainAdmissionTelemetry.publish(admission, 0, System.nanoTime());
            return;
        }

        tasks.addAll(ready.tasks());
        VanillaTerrainAdmissionTelemetry.publish(admission, tasks.size(), System.nanoTime());

        // A task can become cancelled between our HEAD observation and vanilla's atomic-flag check.
        // Re-enter the original synchronized poll only after replenishing a non-empty cohort; the
        // nested call does not refill again and vanilla still owns distance/quota selection.
        cir.setReturnValue(((SectionTaskDynamicQueue)(Object)this).poll(cameraPos));
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void metallum$clearDeferredOwnership(final CallbackInfo ci) {
        BoundedTerrainTaskAdmission<SectionRenderDispatcher.RenderSection.SectionTask> admission =
                metallum$terrainAdmission;
        if (admission != null) {
            admission.clearDeferredAndReset();
        }
    }

    @Inject(method = "clear", at = @At("RETURN"))
    private void metallum$recordClearedQueue(final CallbackInfo ci) {
        BoundedTerrainTaskAdmission<SectionRenderDispatcher.RenderSection.SectionTask> admission =
                metallum$terrainAdmission;
        if (admission != null) {
            VanillaTerrainAdmissionTelemetry.publish(admission, tasks.size(), System.nanoTime());
        }
    }

    @Inject(method = "size", at = @At("HEAD"), cancellable = true)
    private void metallum$includeDeferredInLogicalSize(final CallbackInfoReturnable<Integer> cir) {
        BoundedTerrainTaskAdmission<SectionRenderDispatcher.RenderSection.SectionTask> admission =
                metallum$terrainAdmission;
        if (admission == null || !admission.active()) {
            return;
        }
        synchronized (this) {
            cir.setReturnValue(tasks.size() + admission.deferredSize());
        }
    }

    @Unique
    private BoundedTerrainTaskAdmission<SectionRenderDispatcher.RenderSection.SectionTask>
            metallum$admission() {
        if (metallum$terrainAdmission == null) {
            metallum$terrainAdmission = new BoundedTerrainTaskAdmission<>(
                    BoundedTerrainTaskAdmission.Config.fromSystemProperties(),
                    METALLUM_TASK_OPS
            );
        }
        return metallum$terrainAdmission;
    }

    @Unique
    private int metallum$compactTerminalVanillaTasks() {
        int before = tasks.size();
        tasks.removeIf(METALLUM_TASK_OPS::isTerminal);
        return before - tasks.size();
    }
}
