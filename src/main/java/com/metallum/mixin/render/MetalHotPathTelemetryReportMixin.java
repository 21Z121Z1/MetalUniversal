package com.metallum.mixin.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalDynamicBackingPoolTelemetry;
import com.metallum.client.metal.render.MetalTransientArenaTelemetry;
import com.metallum.client.metal.render.mtl.MetalCommandPacketTelemetry;
import com.metallum.client.metal.render.mtl.MetalHotPathTelemetry;
import com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Periodically emits cumulative counters in a stable, grep-friendly line.
 * Applied only when metallum.hotpath.telemetry=true.
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class MetalHotPathTelemetryReportMixin {
    @Unique
    private static final int metallum$REPORT_INTERVAL = Math.max(
            1,
            Integer.getInteger("metallum.hotpath.telemetry.reportInterval", 300)
    );

    @Unique
    private long metallum$submittedFrames;

    @Inject(method = "submit", at = @At("RETURN"))
    private void metallum$reportHotPathTelemetry(final CallbackInfo ci) {
        this.metallum$submittedFrames++;
        if (this.metallum$submittedFrames % metallum$REPORT_INTERVAL != 0L) {
            return;
        }

        MetalHotPathTelemetry.Snapshot hot = MetalHotPathTelemetry.snapshot();
        MetalRenderStatePacketTelemetry.Snapshot state =
                MetalRenderStatePacketTelemetry.snapshot();
        MetalCommandPacketTelemetry.Snapshot command = MetalCommandPacketTelemetry.snapshot();
        MetalTransientArenaTelemetry.Snapshot arena = MetalTransientArenaTelemetry.snapshot();
        MetalDynamicBackingPoolTelemetry.Snapshot backing =
                MetalDynamicBackingPoolTelemetry.snapshot();

        Metallum.LOGGER.info(
                "[metallum-hotpath] submits={} renderForwarded={} renderSuppressed={} "
                        + "renderOffsetOnly={} computeForwarded={} computeSuppressed={} "
                        + "multiDrawBatches={} multiDrawCommands={} "
                        + "statePacketCalls={} statePacketEntries={} statePacketReplays={} "
                        + "statePacketSingleEntryBypasses={} statePacketCapacityFlushes={} "
                        + "renderCommandPacketCalls={} renderCommandOperations={} "
                        + "renderCommandReplays={} computeCommandPacketCalls={} "
                        + "computeCommandOperations={} computeCommandReplays={} "
                        + "terrainIcbAttempts={} terrainIcbAccepted={} terrainIcbDraws={} "
                        + "terrainIcbFallbacks={} transientWrapperHits={} "
                        + "transientWrapperMisses={} multiUploadCalls={} multiUploadItems={} "
                        + "backingTrims={} backingReleasedBytes={} backingReleasedHandles={} "
                        + "backingRemovedBuckets={} backingPeakBytes={}",
                this.metallum$submittedFrames,
                hot.renderForwardedCalls(),
                hot.renderSuppressedCalls(),
                hot.renderOffsetOnlyCalls(),
                hot.computeForwardedCalls(),
                hot.computeSuppressedCalls(),
                hot.nativeMultiDrawBatches(),
                hot.nativeMultiDrawCommands(),
                state.packetCalls(),
                state.packetEntries(),
                state.legacyReplays(),
                state.singleEntryBypasses(),
                state.capacityFlushes(),
                command.renderPacketCalls(),
                command.renderOperations(),
                command.renderLegacyReplays(),
                command.computePacketCalls(),
                command.computeOperations(),
                command.computeLegacyReplays(),
                command.terrainIcbAttempts(),
                command.terrainIcbAccepted(),
                command.terrainIcbDraws(),
                command.terrainIcbFallbacks(),
                arena.wrapperHits(),
                arena.wrapperMisses(),
                arena.multiUploadCalls(),
                arena.multiUploadItems(),
                backing.trims(),
                backing.releasedBytes(),
                backing.releasedHandles(),
                backing.removedBuckets(),
                backing.peakObservedBytes()
        );
    }
}
