package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Transactional identity for entity-owned geometry which Minecraft deliberately stages as one
 * shared draw.
 *
 * <p>Ordinary entity/piston lifetime generations are positive. Shared batches use negative
 * generations, giving their DrawKey namespace an explicit domain separator even if a synthetic
 * object id ever numerically equals a real object id.</p>
 *
 * <p>The ordered member signature includes each parent's exact per-submit vertex span. Aggregate
 * staged vertex counts are not sufficient: two adjacent entities can grow/shrink by equal amounts,
 * preserving the total while moving the boundary between their vertices. The span makes such a
 * redistribution a new generation. MetalPreviousVertexHistory independently verifies the actual
 * complete staged pipeline/format/topology/vertex/index manifest.</p>
 */
final class MetalSharedBatchMotion {
    static final long FLAME_OBJECT_ID = 0x4D46584C414D45L; // ASCII "MFXLAME".
    private static final int MAX_FLAME_LAYERS = 65_536;

    record Member(long objectId, long generation, int vertexSpan) {
    }

    private record Signature(List<Member> members) {
        Signature {
            members = List.copyOf(members);
        }
    }

    private static @Nullable Signature previousFlameSignature;
    private static long previousFlameGeneration;
    private static @Nullable Signature pendingFlameSignature;
    private static long pendingFlameGeneration;
    private static long nextSharedGeneration = -1L;
    private static boolean frameOpen;
    private static boolean flameBatchOpened;

    private MetalSharedBatchMotion() {
    }

    static void beginFrame() {
        pendingFlameSignature = null;
        pendingFlameGeneration = 0L;
        flameBatchOpened = false;
        frameOpen = true;
    }

    /**
     * Mirrors Minecraft 26.2 FlameFeatureRenderer.prepare only for the number of emitted vertices.
     * The pinned source guard verifies width*1.4, height/scale, h-=0.45 and four fireVertex calls
     * per loop iteration before this implementation may be committed by CI.
     */
    static int flameVertexSpan(final float boundingBoxWidth, final float boundingBoxHeight) {
        if (!Float.isFinite(boundingBoxWidth) || !Float.isFinite(boundingBoxHeight)
                || boundingBoxWidth <= 0.0F || boundingBoxHeight <= 0.0F) {
            return -1;
        }
        float scale = boundingBoxWidth * 1.4F;
        if (!Float.isFinite(scale) || scale <= 0.0F) {
            return -1;
        }
        float height = boundingBoxHeight / scale;
        if (!Float.isFinite(height) || height <= 0.0F) {
            return -1;
        }
        int layers = 0;
        while (height > 0.0F) {
            if (++layers > MAX_FLAME_LAYERS) {
                return -1;
            }
            height -= 0.45F;
        }
        return layers * 4;
    }

    static MetalEntityMotionCapture.Sample beginFlameBatch(final List<Member> members) {
        if (!frameOpen || flameBatchOpened || members == null || members.isEmpty()) {
            return null;
        }
        for (Member member : members) {
            if (member == null || member.generation() <= 0L || member.vertexSpan() <= 0) {
                return null;
            }
        }

        Signature signature = new Signature(members);
        boolean hasPrevious = signature.equals(previousFlameSignature);
        long generation = hasPrevious ? previousFlameGeneration : allocateGeneration();
        if (generation >= 0L) {
            return null;
        }

        flameBatchOpened = true;
        pendingFlameSignature = signature;
        pendingFlameGeneration = generation;
        Matrix4f identity = new Matrix4f();
        return new MetalEntityMotionCapture.Sample(
                FLAME_OBJECT_ID,
                generation,
                identity,
                hasPrevious ? identity : null
        );
    }

    static void commitSubmittedFrame() {
        if (!frameOpen) {
            return;
        }
        // A successfully submitted source frame with no flame batch breaks continuity. Returning
        // flame geometry must seed a fresh generation rather than bridge across the missing frame.
        previousFlameSignature = pendingFlameSignature;
        previousFlameGeneration = pendingFlameSignature == null ? 0L : pendingFlameGeneration;
        pendingFlameSignature = null;
        pendingFlameGeneration = 0L;
        flameBatchOpened = false;
        frameOpen = false;
    }

    static void discardFrame() {
        pendingFlameSignature = null;
        pendingFlameGeneration = 0L;
        flameBatchOpened = false;
        frameOpen = false;
    }

    static void reset() {
        boolean wasOpen = frameOpen;
        previousFlameSignature = null;
        previousFlameGeneration = 0L;
        pendingFlameSignature = null;
        pendingFlameGeneration = 0L;
        nextSharedGeneration = -1L;
        flameBatchOpened = false;
        frameOpen = wasOpen;
    }

    private static long allocateGeneration() {
        long generation = nextSharedGeneration;
        nextSharedGeneration = generation == Long.MIN_VALUE ? -1L : generation - 1L;
        return generation;
    }
}
