package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

@Environment(EnvType.CLIENT)
final class MetalFxMath {
    private MetalFxMath() {
    }

    static float halton(final int oneBasedIndex, final int base) {
        if (oneBasedIndex <= 0 || base <= 1) {
            throw new IllegalArgumentException("Halton index must be positive and base must be greater than one");
        }
        int index = oneBasedIndex;
        float result = 0.0F;
        float fraction = 1.0F / base;
        while (index > 0) {
            result += (index % base) * fraction;
            index /= base;
            fraction /= base;
        }
        return result;
    }

    static Vector2f pixelJitter(final int phase, final int phaseCount) {
        Vector2f result = new Vector2f();
        pixelJitter(result, phase, phaseCount);
        return result;
    }

    static void pixelJitter(final Vector2f destination, final int phase, final int phaseCount) {
        if (phase < 0 || phase >= phaseCount) {
            throw new IllegalArgumentException("Jitter phase outside cycle");
        }
        int index = phase + 1;
        destination.set(halton(index, 2) - 0.5F, halton(index, 3) - 0.5F);
    }

    static Vector2f clipJitter(final Vector2f pixelJitter, final int renderWidth, final int renderHeight) {
        Vector2f result = new Vector2f();
        clipJitter(result, pixelJitter, renderWidth, renderHeight);
        return result;
    }

    static void clipJitter(
            final Vector2f destination,
            final Vector2f pixelJitter,
            final int renderWidth,
            final int renderHeight
    ) {
        if (renderWidth <= 0 || renderHeight <= 0) {
            throw new IllegalArgumentException("Render dimensions must be positive");
        }
        destination.set(
                2.0F * pixelJitter.x / renderWidth,
                -2.0F * pixelJitter.y / renderHeight
        );
    }

    /**
     * Offsets the raster so the sampled position inside each pixel matches the
     * {@code pixelJitter} that is reported to {@code jitterOffsetX/Y}.
     *
     * <p>The reference convention (Apple's MetalFX sample, FSR2's
     * {@code translate(jitter) * proj}, and the porting skill) is
     * {@code clip.xy += clipJitter * clip.w}. Folding that into the
     * projection's third column is only equivalent when {@code w == +z_view},
     * which holds for D3D left-handed projections — that is where the
     * widespread {@code proj[2][0] += ...} idiom comes from. Minecraft's JOML
     * perspective is right handed ({@code m23 == -1}, so {@code w == -z_view}),
     * which flips the sign of the column edit. Subtracting restores the
     * reference offset: with pixel jitter {@code (0.25, -0.5)} the raster now
     * moves {@code (+0.25, -0.5)} screen pixels (x right, y down), matching the
     * value handed to MetalFX instead of negating it.
     */
    static void applyProjectionJitter(final Matrix4f projection, final Vector2f clipJitter) {
        projection.m20(projection.m20() - clipJitter.x);
        projection.m21(projection.m21() - clipJitter.y);
    }

    /**
     * Input-pixel radius needed to cover a CUTOUT sample across the current
     * Temporal jitter and upscale reconstruction footprint.
     */
    static int cutoutReactiveRadius(final float renderScale, final Vector2f pixelJitter) {
        if (!(renderScale > 0.0F) || !Float.isFinite(renderScale)
                || pixelJitter == null
                || !Float.isFinite(pixelJitter.x)
                || !Float.isFinite(pixelJitter.y)) {
            return 3;
        }
        float jitterFootprint = Math.max(Math.abs(pixelJitter.x), Math.abs(pixelJitter.y));
        float upscaleFootprint = Math.max(0.0F, 1.0F / renderScale - 1.0F);
        return Math.clamp((int) Math.ceil(jitterFootprint + upscaleFootprint), 0, 3);
    }

    static void adjustPerspectiveAspect(final Matrix4f projection, final float displayAspect, final float renderAspect) {
        if (!(displayAspect > 0.0F) || !(renderAspect > 0.0F) || !Float.isFinite(displayAspect) || !Float.isFinite(renderAspect)) {
            return;
        }
        float ratio = displayAspect / renderAspect;
        projection.m00(projection.m00() * ratio);
    }

    static float verticalFieldOfViewDegrees(final Matrix4fc projection, final float fallback) {
        float focalLength = projection.m11();
        if (!(focalLength > 0.0F) || !Float.isFinite(focalLength)) {
            return fallback;
        }
        float fieldOfView = (float) Math.toDegrees(2.0D * Math.atan(1.0D / focalLength));
        // Minecraft's perspective FOV slider and its camera effects stay well
        // above 15 degrees. During world initialization the camera state can
        // briefly expose a valid but stale projection (for example ~8
        // degrees); passing that to frame interpolation produces an invalid
        // camera model for the first queued frame.
        return fieldOfView >= 15.0F && fieldOfView < 170.0F && Float.isFinite(fieldOfView)
                ? fieldOfView : fallback;
    }

    static Matrix4f viewMatrix(final Matrix4fc viewRotation, final double cameraX, final double cameraY, final double cameraZ) {
        return viewMatrix(new Matrix4f(), viewRotation, cameraX, cameraY, cameraZ);
    }

    static Matrix4f viewMatrix(
            final Matrix4f destination,
            final Matrix4fc viewRotation,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        return destination.set(viewRotation).translate((float) -cameraX, (float) -cameraY, (float) -cameraZ);
    }

    static Matrix4f viewProjection(final Matrix4fc projection, final Matrix4fc view) {
        return viewProjection(new Matrix4f(), projection, view);
    }

    static Matrix4f viewProjection(
            final Matrix4f destination,
            final Matrix4fc projection,
            final Matrix4fc view
    ) {
        return destination.set(projection).mul(view);
    }

    static Vector2f reconstructMotion(
            final float depth,
            final float currentPixelX,
            final float currentPixelY,
            final int width,
            final int height,
            final Matrix4fc currentViewProjection,
            final Matrix4fc inverseCurrentViewProjection,
            final Matrix4fc previousViewProjection
    ) {
        if (!Float.isFinite(depth) || !Float.isFinite(currentPixelX) || !Float.isFinite(currentPixelY)
                || width <= 0 || height <= 0) {
            return new Vector2f();
        }

        float currentNdcX = (2.0F * (currentPixelX + 0.5F) / width) - 1.0F;
        float currentNdcY = 1.0F - (2.0F * (currentPixelY + 0.5F) / height);
        Vector4f world = new Vector4f(currentNdcX, currentNdcY, depth, 1.0F).mul(inverseCurrentViewProjection);
        if (!Float.isFinite(world.w) || Math.abs(world.w) < 1.0E-6F) {
            return new Vector2f();
        }
        world.div(world.w);

        Vector4f currentClip = new Vector4f(world).mul(currentViewProjection);
        Vector4f previousClip = new Vector4f(world).mul(previousViewProjection);
        if (!Float.isFinite(currentClip.w) || Math.abs(currentClip.w) < 1.0E-6F
                || !Float.isFinite(previousClip.w) || Math.abs(previousClip.w) < 1.0E-6F) {
            return new Vector2f();
        }
        currentClip.div(currentClip.w);
        previousClip.div(previousClip.w);
        return new Vector2f(
                (previousClip.x - currentClip.x) * width * 0.5F,
                (currentClip.y - previousClip.y) * height * 0.5F
        );
    }

    static boolean isFinite(final Matrix4fc matrix) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                if (!Float.isFinite(matrix.get(column, row))) {
                    return false;
                }
            }
        }
        return true;
    }

    static float maxAbsDifference(final Matrix4fc first, final Matrix4fc second) {
        float maximum = 0.0F;
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                maximum = Math.max(maximum, Math.abs(first.get(column, row) - second.get(column, row)));
            }
        }
        return maximum;
    }

    static boolean exceedsSceneCutDistance(
            final double previousX,
            final double previousY,
            final double previousZ,
            final double currentX,
            final double currentY,
            final double currentZ,
            final double distance
    ) {
        if (!(distance > 0.0) || !Double.isFinite(distance)) {
            return true;
        }
        double deltaX = currentX - previousX;
        double deltaY = currentY - previousY;
        double deltaZ = currentZ - previousZ;
        return !Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(deltaZ)
                || deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > distance * distance;
    }
}
