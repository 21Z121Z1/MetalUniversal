package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.core.SectionPos;

/**
 * Conservative admission rules for reusing Sodium REGULAR/WIDE cull trees.
 *
 * <p>This class intentionally does not skip any work. It describes which
 * inputs are potentially reusable so the runtime verifier can compare that
 * candidate against Sodium's ordinary cull result.
 */
public final class SodiumCullReusePolicy {
    public enum AdmissionReason {
        ELIGIBLE,
        NO_REFERENCE,
        NOT_CAMERA_CHANGE,
        GRAPH_DIRTY,
        PARAMETERS_CHANGED,
        CAMERA_SECTION_CHANGED,
        TREE_REPLACED,
        REFERENCE_HAS_TASKS,
        DISTANCE_CLASSIFICATION_CHANGED,
        NEARBY_VISIBILITY_CHANGED
    }

    public record Inputs(
            Viewport viewport,
            float regularDistance,
            float localDistance,
            boolean occlusion,
            long graphGeneration
    ) {
    }

    private SodiumCullReusePolicy() {
    }

    public static AdmissionReason evaluate(
            Inputs reference,
            Inputs current,
            boolean cameraChanged,
            boolean needsGraphUpdate,
            boolean referenceTreesStillCurrent,
            boolean referenceTaskListEmpty,
            SectionStorage sections,
            int minSectionY,
            int maxSectionY
    ) {
        if (reference == null) {
            return AdmissionReason.NO_REFERENCE;
        }
        if (!cameraChanged) {
            return AdmissionReason.NOT_CAMERA_CHANGE;
        }
        if (needsGraphUpdate || reference.graphGeneration() != current.graphGeneration()) {
            return AdmissionReason.GRAPH_DIRTY;
        }
        if (reference.occlusion() != current.occlusion()
                || Float.floatToRawIntBits(reference.regularDistance())
                != Float.floatToRawIntBits(current.regularDistance())
                || Float.floatToRawIntBits(reference.localDistance())
                != Float.floatToRawIntBits(current.localDistance())) {
            return AdmissionReason.PARAMETERS_CHANGED;
        }

        SectionPos referenceOrigin = reference.viewport().getChunkCoord();
        SectionPos currentOrigin = current.viewport().getChunkCoord();
        if (!referenceOrigin.equals(currentOrigin)) {
            return AdmissionReason.CAMERA_SECTION_CHANGED;
        }
        if (!referenceTreesStillCurrent) {
            return AdmissionReason.TREE_REPLACED;
        }
        if (!referenceTaskListEmpty) {
            return AdmissionReason.REFERENCE_HAS_TASKS;
        }
        if (!sameDistanceClassification(
                reference.viewport().getTransform(),
                current.viewport().getTransform(),
                currentOrigin,
                current.regularDistance(),
                minSectionY,
                maxSectionY
        )) {
            return AdmissionReason.DISTANCE_CLASSIFICATION_CHANGED;
        }
        if (!sameNearbyVisibility(reference.viewport(), current.viewport(), sections)) {
            return AdmissionReason.NEARBY_VISIBILITY_CHANGED;
        }
        return AdmissionReason.ELIGIBLE;
    }

    /**
     * Compares the exact boolean outcomes used by Sodium's cylindrical
     * distance gate without attempting to duplicate its graph traversal.
     *
     * <p>XZ and Y terms are compared independently. This is deliberately
     * stronger than merely comparing their final conjunction, so a candidate
     * may be rejected even when reuse would have happened to be safe.
     */
    public static boolean sameDistanceClassification(
            CameraTransform previous,
            CameraTransform current,
            SectionPos origin,
            float maxDistance,
            int minSectionY,
            int maxSectionY
    ) {
        if (sameTransform(previous, current)) {
            return true;
        }

        int radius = (int) Math.ceil(maxDistance / 16.0f) + 2;
        float squaredLimit = maxDistance * maxDistance;

        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            float previousX = axisDistance(x, previous.intX, previous.fracX);
            float currentX = axisDistance(x, current.intX, current.fracX);
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                float previousZ = axisDistance(z, previous.intZ, previous.fracZ);
                float currentZ = axisDistance(z, current.intZ, current.fracZ);
                boolean previousInside = previousX * previousX + previousZ * previousZ < squaredLimit;
                boolean currentInside = currentX * currentX + currentZ * currentZ < squaredLimit;
                if (previousInside != currentInside) {
                    return false;
                }
            }
        }

        for (int y = minSectionY; y <= maxSectionY; y++) {
            boolean previousInside = Math.abs(axisDistance(y, previous.intY, previous.fracY)) < maxDistance;
            boolean currentInside = Math.abs(axisDistance(y, current.intY, current.fracY)) < maxDistance;
            if (previousInside != currentInside) {
                return false;
            }
        }

        return true;
    }

    public static boolean sameNearbyVisibility(
            Viewport previous,
            Viewport current,
            SectionStorage sections
    ) {
        SectionPos origin = current.getChunkCoord();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    RenderSection section = sections.getCurrent(
                            origin.getX() + dx,
                            origin.getY() + dy,
                            origin.getZ() + dz
                    );
                    if (section == null) {
                        continue;
                    }
                    if (OcclusionCuller.isWithinNearbySectionFrustum(previous, section)
                            != OcclusionCuller.isWithinNearbySectionFrustum(current, section)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    static boolean sameTransform(CameraTransform left, CameraTransform right) {
        return left.intX == right.intX
                && left.intY == right.intY
                && left.intZ == right.intZ
                && Float.floatToRawIntBits(left.fracX) == Float.floatToRawIntBits(right.fracX)
                && Float.floatToRawIntBits(left.fracY) == Float.floatToRawIntBits(right.fracY)
                && Float.floatToRawIntBits(left.fracZ) == Float.floatToRawIntBits(right.fracZ);
    }

    static float axisDistance(int sectionCoordinate, int cameraInteger, float cameraFraction) {
        int relativeOrigin = (sectionCoordinate << 4) - cameraInteger;
        int minimum = relativeOrigin - 1;
        int maximum = relativeOrigin + 17;
        int nearest = Math.max(minimum, Math.min(0, maximum));
        return nearest - cameraFraction;
    }
}
