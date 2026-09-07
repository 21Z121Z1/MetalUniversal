package com.metallum.client.metal.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Fail-closed per-source-frame proof that every staged draw belonging to an object which requires
 * deforming-geometry motion has an exact previous-position replay candidate.
 *
 * <p>The proof is transactional with {@link MetalPreviousVertexHistory}: a required object is
 * complete only when the entire current draw manifest exactly matches the previous successfully
 * submitted source frame and every matching draw produced an exact replay plan. Unsupported
 * auxiliary geometry marks the object failed immediately.</p>
 */
final class MetalExactMotionCoverage {
    private record ObjectKey(long objectId, long generation) {
    }

    private static final class Status {
        int exactPlans;
        boolean failed;
        String reason;
    }

    private static final Map<ObjectKey, Status> REQUIRED = new HashMap<>();

    private MetalExactMotionCoverage() {
    }

    static void beginFrame() {
        REQUIRED.clear();
    }

    static void reset() {
        REQUIRED.clear();
    }

    static void require(final MetalEntityMotionCapture.Sample sample) {
        if (sample != null) {
            REQUIRED.computeIfAbsent(key(sample), ignored -> new Status());
        }
    }

    static boolean required(final MetalEntityMotionCapture.Sample sample) {
        return sample != null && REQUIRED.containsKey(key(sample));
    }

    static void fail(final MetalEntityMotionCapture.Sample sample, final String reason) {
        if (sample == null) {
            return;
        }
        Status status = REQUIRED.get(key(sample));
        if (status != null) {
            status.failed = true;
            if (status.reason == null) {
                status.reason = reason;
            }
        }
    }

    static void recordExactPlan(final MetalPreviousVertexHistory.DrawToken token) {
        if (token == null) {
            return;
        }
        MetalPreviousVertexHistory.ObjectKey object = token.key().object();
        Status status = REQUIRED.get(new ObjectKey(object.objectId(), object.generation()));
        if (status != null && !status.failed) {
            status.exactPlans++;
        }
    }

    static boolean complete() {
        for (Map.Entry<ObjectKey, Status> entry : REQUIRED.entrySet()) {
            Status status = entry.getValue();
            if (status.failed) {
                return false;
            }
            ObjectKey object = entry.getKey();
            int matchingDraws = MetalPreviousVertexHistory.matchingManifestDrawCount(
                    object.objectId(), object.generation()
            );
            if (matchingDraws <= 0 || status.exactPlans != matchingDraws) {
                return false;
            }
        }
        return true;
    }

    static String firstFailureReason() {
        for (Status status : REQUIRED.values()) {
            if (status.failed) {
                return status.reason;
            }
        }
        return null;
    }

    private static ObjectKey key(final MetalEntityMotionCapture.Sample sample) {
        return new ObjectKey(sample.objectId(), sample.generation());
    }
}
