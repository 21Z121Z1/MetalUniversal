package com.metallum.client.metal.render;

import java.util.Objects;

/**
 * Fail-closed admission contract for GPU-authored terrain ICB execution.
 *
 * <p>Starting a visibility probe or creating an ICB is not activation proof.
 * This policy requires a generation-valid producer, bounded source/candidate
 * lists and non-zero effective encode/execute counters before a run can be
 * labelled active. The existing CPU/indirect path remains the fallback when
 * any field is unavailable.</p>
 */
public final class TerrainGpuIcbAdmission {
    public enum Path { ALL_VISIBLE, VISIBLE_TWO_STAGE, VISIBLE_FUSED }

    private TerrainGpuIcbAdmission() {
    }

    public static Decision decide(final Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (!inputs.requested()) return rejected(Path.ALL_VISIBLE, "feature-disabled");
        if (!inputs.metal4Available() || !inputs.compilerAvailable()) {
            return rejected(path(inputs), "metal4-capability-unavailable");
        }
        if (!inputs.producerIdentityValid() || !inputs.generationMatches()) {
            return rejected(path(inputs), "producer-generation-unavailable");
        }
        if (inputs.sourceDrawCount() <= 0 || inputs.candidateCount() <= 0) {
            return rejected(path(inputs), "bounded-source-or-candidate-list-unavailable");
        }
        if (inputs.effectiveEncodedCommands() <= 0L) {
            return rejected(path(inputs), "effective-encode-counter-zero");
        }
        if (inputs.effectiveExecutedCommands() <= 0L) {
            return rejected(path(inputs), "effective-execute-counter-zero");
        }
        return new Decision(true, path(inputs), "effective-counters-confirmed");
    }

    private static Path path(final Inputs inputs) {
        if (inputs.fusedVisible()) return Path.VISIBLE_FUSED;
        if (inputs.visibleCulling()) return Path.VISIBLE_TWO_STAGE;
        return Path.ALL_VISIBLE;
    }

    private static Decision rejected(final Path path, final String reason) {
        return new Decision(false, path, reason);
    }

    public record Inputs(
            boolean requested,
            boolean metal4Available,
            boolean compilerAvailable,
            boolean producerIdentityValid,
            boolean generationMatches,
            boolean visibleCulling,
            boolean fusedVisible,
            int sourceDrawCount,
            int candidateCount,
            long effectiveEncodedCommands,
            long effectiveExecutedCommands
    ) {
        public Inputs {
            if (sourceDrawCount < 0 || candidateCount < 0
                    || effectiveEncodedCommands < -1L || effectiveExecutedCommands < -1L) {
                throw new IllegalArgumentException("terrain ICB counters must be non-negative or unavailable");
            }
            if (fusedVisible && !visibleCulling) {
                throw new IllegalArgumentException("fused visibility requires the visible-culling lane");
            }
        }
    }

    public record Decision(boolean admitted, Path path, String reason) {
        public Decision {
            Objects.requireNonNull(path, "path");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("terrain ICB decision reason must not be blank");
            }
        }
    }
}
