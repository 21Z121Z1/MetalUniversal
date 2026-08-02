package com.metallum.client.metal.render;

import java.util.Objects;

/**
 * Produces a single fragment source for a final shader followed by a pure color
 * transform. The planner rejects transforms that sample neighbors, use depth,
 * write multiple targets or depend on derivatives outside the final program.
 */
final class IrisMetalFinalColorFusion {
    record Candidate(
            String finalFragmentSource,
            String colorFunctionSource,
            String colorFunctionName,
            boolean singleColorOutput,
            boolean colorTransformIsPointwise,
            boolean colorTransformUsesOnlyInputColor
    ) {
        Candidate {
            Objects.requireNonNull(finalFragmentSource, "finalFragmentSource");
            Objects.requireNonNull(colorFunctionSource, "colorFunctionSource");
            Objects.requireNonNull(colorFunctionName, "colorFunctionName");
        }
    }

    record Result(boolean fused, String source, String rejectionReason) {
        static Result rejected(final String reason) {
            return new Result(false, "", reason);
        }
    }

    private IrisMetalFinalColorFusion() {
    }

    static Result fuse(final Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!IrisMetalOptimizationPlan.ENABLE_FINAL_COLOR_FUSION) {
            return Result.rejected("feature disabled");
        }
        if (!candidate.singleColorOutput()) {
            return Result.rejected("final program has multiple observable outputs");
        }
        if (!candidate.colorTransformIsPointwise()) {
            return Result.rejected("color transform is not pointwise");
        }
        if (!candidate.colorTransformUsesOnlyInputColor()) {
            return Result.rejected("color transform depends on external resources");
        }
        if (!candidate.colorFunctionName().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return Result.rejected("invalid color function identifier");
        }

        String marker = "/* METALLUM_FINAL_COLOR_OUTPUT */";
        int markerIndex = candidate.finalFragmentSource().indexOf(marker);
        if (markerIndex < 0) {
            return Result.rejected("final source has no explicit fusion marker");
        }
        int expressionStart = markerIndex + marker.length();
        int semicolon = candidate.finalFragmentSource().indexOf(';', expressionStart);
        if (semicolon < 0) {
            return Result.rejected("fusion marker is not followed by an output expression");
        }
        String expression = candidate.finalFragmentSource().substring(expressionStart, semicolon).trim();
        if (expression.isEmpty()) {
            return Result.rejected("fusion marker expression is empty");
        }

        String replacement = marker + " " + candidate.colorFunctionName() + "(" + expression + ")";
        String fused = candidate.colorFunctionSource()
                + "\n\n"
                + candidate.finalFragmentSource().substring(0, markerIndex)
                + replacement
                + candidate.finalFragmentSource().substring(semicolon);
        return new Result(true, fused, "");
    }
}
