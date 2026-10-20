package com.metallum.client.metal.render;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Frame-bound immutable diagnostics retained until asynchronous GPU readback completes. */
final class MetalRawMotionEvidence {
    record Snapshot(String sourceCommit,long frameId,long historyEpoch,boolean diagnosticOnly,String observationPoint,
                    MetalMotionDrawCoverage.Census sourceCensus,
                    Map<String,MetalCloudMotion.LayerDiagnostics> cloudLayers) {}

    private final String sourceCommit;
    private final FrameSynthesisContract.FrameStamp stamp;
    private final AtomicReference<Snapshot> frozen=new AtomicReference<>();

    MetalRawMotionEvidence(String sourceCommit,FrameSynthesisContract.FrameStamp stamp) {
        this.sourceCommit=Objects.requireNonNull(sourceCommit);
        this.stamp=Objects.requireNonNull(stamp);
    }

    void freeze(MetalMotionDrawCoverage.Census census,Map<String,MetalCloudMotion.LayerDiagnostics> clouds) {
        // census() already copies the live inventories. Do not read those live
        // globals again from the GPU completion callback or a later client tick.
        var source=Objects.requireNonNull(census).sourceFrame();
        if(source==null || !source.collected() || !source.closed()
                || source.frameId()!=stamp.frameId() || source.epoch()!=stamp.historyEpoch()) {
            throw new IllegalStateException("Raw motion source census is incomplete or mismatched: expected "
                    +stamp.frameId()+"/"+stamp.historyEpoch()+", observed "
                    +(source==null ? "missing" : source.frameId()+"/"+source.epoch()+", closed="+source.closed()));
        }
        var snapshot=new Snapshot(sourceCommit,stamp.frameId(),stamp.historyEpoch(),true,
                "source motion closed before later GUI",census,Map.copyOf(clouds));
        if(!frozen.compareAndSet(null,snapshot))throw new IllegalStateException("Raw motion source evidence already frozen");
    }

    Snapshot requireSnapshot() {
        var snapshot=frozen.get();
        if(snapshot==null)throw new IllegalStateException("Raw motion completed before its source census was frozen");
        return snapshot;
    }
}
