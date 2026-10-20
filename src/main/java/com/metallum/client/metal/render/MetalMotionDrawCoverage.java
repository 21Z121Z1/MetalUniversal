package com.metallum.client.metal.render;

import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Map;
import java.util.TreeMap;

/** Source execution evidence. Counts never stand in for per-pixel validity or device capability. */
public final class MetalMotionDrawCoverage {
    public record Snapshot(long sourceDraws, long ownedColorDraws, long unownedColorDraws,
                           long depthBoundsDraws, long transmittanceDraws, long accumulateDraws,
                           long duplicateColorDraws, long queuedReplays, long encodedReplays,
                           long encodedDisocclusionReplays) {}
    private static long source, owned, unowned, depthBounds, transmittance, accumulate, duplicates, queued, encoded;
    private static long ownedInvalid;
    private static final boolean VALIDATION = Boolean.getBoolean("metallum.metalfx.captureMotionValidation");
    private static final MetalMotionFrameManifest FRAME_MANIFEST=new MetalMotionFrameManifest();
    private static MetalMotionFrameManifest.Snapshot lastSourceFrame=FRAME_MANIFEST.snapshot();
    private static MetalUiDrawCoverage.Snapshot lastUiFrame=MetalUiDrawCoverage.snapshot();
    private static boolean scene;
    private static int motionReplayDepth;
    private static final Map<String, long[]> PIPELINES = new TreeMap<>();
    private static final Map<RenderPipeline, MetalMotionProgramIdentity.Identity> PROGRAM_IDENTITIES = new java.util.IdentityHashMap<>();
    private static final Map<String, MetalMotionProgramIdentity.Description> PROGRAM_DESCRIPTIONS = new TreeMap<>();
    private static final Map<String, long[]> PROGRAMS = new TreeMap<>();
    private static final Map<String,Map<String,MetalShaderInputCapture.Snapshot>> PROGRAM_INPUTS=new TreeMap<>();
    private static final java.util.Set<String> PROGRAM_INPUT_OVERFLOW=new java.util.HashSet<>();
    private record OwnerKey(FrameSynthesisContract.ProducerDomain domain, long id, long generation) {}
    private static final Map<OwnerKey, String> FRAME_OWNER_CLASSES = new java.util.HashMap<>();
    private static final Map<String, long[]> OWNERS = new TreeMap<>();

    public record PipelineCounts(long actualDraws, long hookedExecutions, long ownedColorExecutions,
                                 long encodedMotionDraws, long internalMotionReplays, long encodedDisocclusionDraws) {}
    public record OwnerCounts(long capturedStates, long sourceColorDraws, long matchingGeometryDraws,
                              long matchingTransformDraws, long matchingSurfaceDraws, long encodedMotionDraws,
                              long encodedDisocclusionDraws) {}
    public record ShaderInputs(Map<String,MetalShaderInputCapture.Snapshot> observedCompiles,boolean overflow) {}
    public record ProgramCounts(MetalMotionProgramIdentity.@Nullable Description source,
                                long actualDraws, long hookedExecutions, long ownedColorExecutions,
                                long encodedMotionDraws, long internalMotionReplays, long encodedDisocclusionDraws,
                                ShaderInputs shaderInputs) {}
    public record Census(boolean rawMotionValidation, Map<String, PipelineCounts> pipelines,
                         Map<String, ProgramCounts> programs, Map<String, OwnerCounts> owners,
                         Snapshot lastFrame, MetalEntityMotionCapture.Diagnostics capture, MetalMotionFrameManifest.Snapshot sourceFrame,MetalUiDrawCoverage.Snapshot uiFrame) {}

    private MetalMotionDrawCoverage() {}

    static void beginFrame() {
        scene = false;
        if(FRAME_MANIFEST.collecting())lastSourceFrame=FRAME_MANIFEST.snapshot();
        var ui=MetalUiDrawCoverage.snapshot();
        if(ui.frameId()>0)lastUiFrame=ui;
        FRAME_MANIFEST.reset();
        MetalUiDrawCoverage.reset();
        motionReplayDepth = 0;
        FRAME_OWNER_CLASSES.clear();
        source = owned = unowned = depthBounds = transmittance = accumulate = duplicates = queued = encoded = 0;
        ownedInvalid = 0;
    }

    static void attachOwner(final Object state, final MetalEntityMotionCapture.Sample sample) {
        if (!VALIDATION || state == null || sample == null) return;
        String name = state.getClass().getName();
        if (state.getClass() == net.minecraft.client.renderer.entity.state.EntityRenderState.class
                && ((net.minecraft.client.renderer.entity.state.EntityRenderState) state).entityType != null) {
            name += "[" + ((net.minecraft.client.renderer.entity.state.EntityRenderState) state)
                    .entityType.builtInRegistryHolder().key().identifier() + "]";
        }
        FRAME_OWNER_CLASSES.put(new OwnerKey(sample.domain(), sample.objectId(), sample.generation()), name);
        OWNERS.computeIfAbsent(name, ignored -> new long[7])[0]++;
    }

    private static long[] ownerCounts(final MetalEntityMotionCapture.Sample sample) {
        String name = FRAME_OWNER_CLASSES.getOrDefault(new OwnerKey(sample.domain(), sample.objectId(), sample.generation()),
                "domain:" + sample.domain());
        return OWNERS.computeIfAbsent(name, ignored -> new long[7]);
    }

    static void stagedHistory(final MetalEntityMotionCapture.Sample sample, final MetalPreviousVertexHistory.DrawToken token) {
        if (!VALIDATION) return;
        long[] counts = ownerCounts(sample);
        if (MetalPreviousVertexHistory.matchingManifestDrawCount(sample.objectId(), sample.generation()) > 0) counts[2]++;
        if (MetalPreviousVertexHistory.matchedPreviousDrawTransform(token) != null) counts[3]++;
        if (MetalPreviousVertexHistory.matchingSurfaceManifest(token)) counts[4]++;
    }

    static void beginScene() { scene = true; }
    static void endScene() { scene = false; }
    static void beginMotionReplay() { motionReplayDepth++; }
    static void endMotionReplay() {
        if (motionReplayDepth <= 0) throw new IllegalStateException("Unbalanced motion replay scope");
        motionReplayDepth--;
    }

    static MetalMotionProgramIdentity.Identity programIdentity(RenderPipeline pipeline) {
        if(PROGRAM_IDENTITIES.size()>=1024 && !PROGRAM_IDENTITIES.containsKey(pipeline))PROGRAM_IDENTITIES.clear();
        return PROGRAM_IDENTITIES.computeIfAbsent(pipeline,MetalMotionProgramIdentity::of);
    }

    private static void countProgram(final RenderPipeline pipeline, final int counter, final long count) {
        var identity = programIdentity(pipeline);
        PROGRAM_DESCRIPTIONS.putIfAbsent(identity.key(), identity.source());
        PROGRAMS.computeIfAbsent(identity.key(), ignored -> new long[6])[counter] += count;
    }

    static boolean needsSourceObservation() {return VALIDATION || FRAME_MANIFEST.collecting();}

    static void bindSourceFrame(FrameSynthesisContract.FrameStamp stamp) {
        FRAME_MANIFEST.begin(stamp.frameId(),stamp.historyEpoch());
    }

    static void closeSourceObservation(FrameSynthesisContract.FrameStamp stamp) {
        var current=FRAME_MANIFEST.snapshot();
        if(current.frameId()!=stamp.frameId() || current.epoch()!=stamp.historyEpoch())return;
        FRAME_MANIFEST.closeObservation();lastSourceFrame=FRAME_MANIFEST.snapshot();
    }

    static boolean sourceFrameLinked(FrameSynthesisContract.FrameStamp stamp) {
        return FRAME_MANIFEST.snapshot().matches(stamp.frameId(),stamp.historyEpoch());
    }

    static MetalMotionFrameManifest.Snapshot sourceFrameManifest() {return FRAME_MANIFEST.snapshot();}

    private static void frameEvent(RenderPipeline pipeline,MetalMotionFrameManifest.Event event,long count) {
        if(!FRAME_MANIFEST.collecting() || pipeline==null)return;
        var identity=programIdentity(pipeline);
        boolean writesColor=pipeline.getColorTargetStates().stream().anyMatch(target->target!=null && target.writeMask()!=0);
        FRAME_MANIFEST.record(identity.key(),writesColor,event,count);
    }

    /** Independent denominator from the production backend, including direct and unowned draws. */
    static void actualDraw(final MetalCompiledRenderPipeline compiled, final int count) {
        actualDraw(compiled,count,false);
    }

    static void actualDraw(final MetalCompiledRenderPipeline compiled,final int count,final boolean indirect) {
        if(!scene || compiled==null || count<=0)return;
        RenderPipeline source=compiled.sourcePipeline();
        String name=source==null ? "backend-only:"+compiled.validationPipelineId() : source.getLocation().toString();
        if(motionReplayDepth==0 && FRAME_MANIFEST.collecting()) {
            if(source==null)FRAME_MANIFEST.record(name,true,MetalMotionFrameManifest.Event.ACTUAL,count);
            else frameEvent(source,MetalMotionFrameManifest.Event.ACTUAL,count);
            if(indirect) {
                if(source==null)FRAME_MANIFEST.record(name,true,MetalMotionFrameManifest.Event.INDIRECT,count);
                else frameEvent(source,MetalMotionFrameManifest.Event.INDIRECT,count);
            }
        }
        if(VALIDATION) {
            int counter=motionReplayDepth>0 ? 4 : 0;
            PIPELINES.computeIfAbsent(name,ignored->new long[6])[counter]+=count;
            if(source==null)PROGRAMS.computeIfAbsent(name,ignored->new long[6])[counter]+=count;
            else {
                countProgram(source,counter,count);
                var inputs=compiled.shaderInputs();
                if(inputs!=null) {
                    String key=programIdentity(source).key();
                    var variants=PROGRAM_INPUTS.computeIfAbsent(key,ignored->new TreeMap<>());
                    if(variants.containsKey(inputs.contentSha256()) || variants.size()<4)
                        variants.put(inputs.contentSha256(),inputs);
                    else PROGRAM_INPUT_OVERFLOW.add(key);
                }
            }
        }
    }

    static void hookedDraw(final MetalPreviousVertexReplay.@Nullable SourceState state, final @Nullable OitStage stage,
                           final MetalEntityMotionCapture.@Nullable Sample sample) {
        if(scene && state!=null && motionReplayDepth==0) {
            frameEvent(state.pipeline(),MetalMotionFrameManifest.Event.HOOKED,1);
            if((stage==null || stage==OitStage.ACCUMULATE) && sample!=null)
                frameEvent(state.pipeline(),MetalMotionFrameManifest.Event.OWNED,1);
        }
        if (VALIDATION && scene && state != null) {
            long[] counts = PIPELINES.computeIfAbsent(state.pipeline().getLocation().toString(), ignored -> new long[6]);
            counts[1]++;
            countProgram(state.pipeline(), 1, 1);
            if ((stage == null || stage == OitStage.ACCUMULATE) && sample != null) {
                counts[2]++;
                countProgram(state.pipeline(), 2, 1);
                ownerCounts(sample)[1]++;
            }
        }
    }

    public static Census census() {
        Map<String, PipelineCounts> copy = new TreeMap<>();
        PIPELINES.forEach((name, counts) -> copy.put(name, new PipelineCounts(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5])));
        Map<String, OwnerCounts> owners = new TreeMap<>();
        OWNERS.forEach((name, counts) -> owners.put(name, new OwnerCounts(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6])));
        Map<String, ProgramCounts> programs = new TreeMap<>();
        PROGRAMS.forEach((key, counts) -> programs.put(key, new ProgramCounts(PROGRAM_DESCRIPTIONS.get(key),
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5],
                new ShaderInputs(Map.copyOf(PROGRAM_INPUTS.getOrDefault(key,Map.of())),PROGRAM_INPUT_OVERFLOW.contains(key)))));
        return new Census(VALIDATION, Map.copyOf(copy), Map.copyOf(programs), Map.copyOf(owners),
                snapshot(), MetalEntityMotionCapture.diagnostics(),lastSourceFrame,
                MetalUiDrawCoverage.forSourceFrame(lastSourceFrame.frameId(),lastSourceFrame.epoch(),
                        MetalUiDrawCoverage.snapshot(),lastUiFrame));
    }

    static void sourceExecution(final @Nullable OitStage stage,
                                final MetalEntityMotionCapture.@Nullable Sample sample, final boolean duplicate) {
        source++;
        if (stage == OitStage.DEPTH_BOUNDS) depthBounds++;
        if (stage == OitStage.TRANSMITTANCE) transmittance++;
        if (stage == OitStage.ACCUMULATE) accumulate++;
        if (stage != null && stage != OitStage.ACCUMULATE) return;
        if (duplicate) duplicates++;
        else if (sample == null) unowned++;
        else owned++;
    }

    static void queued() { queued++; }
    static void encoded(final MetalEntityMotionCapture.Sample sample, final RenderPipeline source) {
        encoded++;
        frameEvent(source,MetalMotionFrameManifest.Event.EXACT,1);
        if (VALIDATION) {
            ownerCounts(sample)[5]++;
            PIPELINES.computeIfAbsent(source.getLocation().toString(), ignored -> new long[6])[3]++;
            countProgram(source, 3, 1);
        }
    }

    static void ownedInvalidEncoded(final MetalEntityMotionCapture.Sample sample, final RenderPipeline source) {
        ownedInvalid++;
        frameEvent(source,MetalMotionFrameManifest.Event.DISOCCLUSION_ONLY,1);
        if (VALIDATION) {
            ownerCounts(sample)[6]++;
            PIPELINES.computeIfAbsent(source.getLocation().toString(), ignored -> new long[6])[5]++;
            countProgram(source, 5, 1);
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(source, owned, unowned, depthBounds, transmittance, accumulate, duplicates, queued, encoded, ownedInvalid);
    }
}
