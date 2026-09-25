package com.metallum.client.metal.framegraph;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.metallum.client.metal.framegraph.ResourceDescriptor.PipelineStage;

/**
 * One declared unit of work. A pass names the resources it touches and how, and
 * nothing else: it holds no Metal object, records no commands, and does not know
 * which slot its resources will land in.
 */
public record FramePass(
        String name,
        Phase phase,
        Map<SemanticResource, Access> resources,
        Set<String> dependsOn,
        int declarationOrder
) {
    public FramePass {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Pass name must not be blank");
        }
        Objects.requireNonNull(phase, "phase");
        resources = Map.copyOf(resources);
        dependsOn = Set.copyOf(dependsOn);
        if (declarationOrder < 0) {
            throw new IllegalArgumentException("declarationOrder must not be negative");
        }
    }

    public enum Access {
        READ(false, true),
        WRITE(true, false),
        READ_WRITE(true, true);

        private final boolean writes;
        private final boolean reads;

        Access(final boolean writes, final boolean reads) {
            this.writes = writes;
            this.reads = reads;
        }

        public boolean writes() {
            return writes;
        }

        public boolean reads() {
            return reads;
        }
    }

    /**
     * Canonical scene order. Phases fix the coarse sequence of a frame so the
     * compiled order is stable no matter which extensions are loaded; hazards
     * and explicit dependencies order passes inside one phase.
     *
     * <p>Every phase here corresponds to work this renderer actually performs.
     * There is deliberately no tone-map phase: vanilla Minecraft has no separate
     * tone-map pass, and a shader pack that adds one declares it under
     * {@link #SHADER_PACK_COMPOSITE}.</p>
     */
    public enum Phase {
        /** Shadow map rasterisation. Shader-pack only. */
        SHADOW(PipelineStage.FRAGMENT),
        /** The world pass and its MRT attachments: colour, depth, motion, coverage. */
        WORLD_MRT(PipelineStage.FRAGMENT),
        /** Translucent geometry, blended over the opaque result. */
        TRANSPARENCY(PipelineStage.FRAGMENT),
        /** Camera and object motion merged into the scaler's motion input. */
        MOTION_MERGE(PipelineStage.COMPUTE),
        /** Coverage and depth turned into the temporal scaler's reactive mask. */
        REACTIVE_MASK(PipelineStage.COMPUTE),
        /** Shader-pack deferred lighting. */
        SHADER_PACK_DEFERRED(PipelineStage.FRAGMENT),
        /** Shader-pack composite chain, including any tone mapping it performs. */
        SHADER_PACK_COMPOSITE(PipelineStage.FRAGMENT),
        /** MetalFX temporal or spatial scaling. */
        TEMPORAL_UPSCALE(PipelineStage.SCALER),
        /** User interface rasterisation, always at native display resolution. */
        UI(PipelineStage.FRAGMENT),
        /** Scene and UI composed into the presenter's source frame. */
        UI_COMPOSITION(PipelineStage.FRAGMENT),
        /** MetalFX frame interpolation between two real frames. */
        FRAME_INTERPOLATION(PipelineStage.SCALER),
        /** Handing a finished frame to the presenter. */
        PRESENT(PipelineStage.PRESENT);

        private final PipelineStage executionStage;

        Phase(final PipelineStage executionStage) {
            this.executionStage = executionStage;
        }

        public PipelineStage executionStage() {
            return executionStage;
        }
    }
}
