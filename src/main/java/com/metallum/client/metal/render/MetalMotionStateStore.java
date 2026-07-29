package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Frame-transactional current/previous transform storage.
 *
 * <p>Renderers may observe an object more than once in a frame. The pending
 * map is replaced only after the frame's output was successfully encoded, so
 * a failed frame cannot destroy the previous transform needed by the next
 * valid frame. The caller supplies a generation in addition to an object id;
 * this prevents an id-reused object from inheriting unrelated history.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalMotionStateStore {
    record ObjectKey(long id, long generation) {
    }

    private final Map<ObjectKey, Matrix4f> previous = new HashMap<>();
    private final Map<ObjectKey, Matrix4f> pending = new HashMap<>();
    private boolean frameOpen;
    private long missingPreviousCount;

    void beginFrame() {
        pending.clear();
        frameOpen = true;
    }

    void observe(final ObjectKey key, final Matrix4fc currentTransform) {
        if (!frameOpen) {
            throw new IllegalStateException("Motion state observed outside a frame transaction");
        }
        observeValidated(key, currentTransform);
    }

    boolean observeIfFrameOpen(final ObjectKey key, final Matrix4fc currentTransform) {
        return frameOpen && observeValidated(key, currentTransform);
    }

    private boolean observeValidated(final ObjectKey key, final Matrix4fc currentTransform) {
        if (key == null || currentTransform == null || !MetalFxMath.isFinite(currentTransform)) {
            return false;
        }
        pending.put(key, new Matrix4f(currentTransform));
        return true;
    }

    @Nullable
    Matrix4f previous(final ObjectKey key) {
        Matrix4f value = previous.get(key);
        if (value == null) {
            missingPreviousCount++;
            return null;
        }
        return new Matrix4f(value);
    }

    /**
     * Returns history only when the object's translation stayed within the
     * renderer's continuity bound. A large jump is a teleport/recreated scene,
     * not a velocity that should be sent to the interpolator; the current value
     * remains pending so the next frame can resume with a fresh previous pose.
     */
    @Nullable
    Matrix4f previousIfContinuous(
            final ObjectKey key,
            final Matrix4fc currentTransform,
            final float maxTranslationDelta
    ) {
        Matrix4f value = previous.get(key);
        if (value == null) {
            missingPreviousCount++;
            return null;
        }
        if (currentTransform == null || !MetalFxMath.isFinite(currentTransform)
                || !Float.isFinite(maxTranslationDelta) || maxTranslationDelta < 0.0F
                || Math.abs(currentTransform.m30() - value.m30()) > maxTranslationDelta
                || Math.abs(currentTransform.m31() - value.m31()) > maxTranslationDelta
                || Math.abs(currentTransform.m32() - value.m32()) > maxTranslationDelta) {
            return null;
        }
        return new Matrix4f(value);
    }

    boolean hasPrevious(final ObjectKey key) {
        return previous.containsKey(key);
    }

    void commitSubmittedFrame() {
        if (!frameOpen) {
            return;
        }
        previous.clear();
        for (Map.Entry<ObjectKey, Matrix4f> entry : pending.entrySet()) {
            previous.put(entry.getKey(), new Matrix4f(entry.getValue()));
        }
        pending.clear();
        frameOpen = false;
    }

    void discardFrame() {
        pending.clear();
        frameOpen = false;
    }

    void reset() {
        boolean wasOpen = frameOpen;
        previous.clear();
        pending.clear();
        frameOpen = wasOpen;
    }

    long missingPreviousCount() {
        return missingPreviousCount;
    }

    void clearStatistics() {
        missingPreviousCount = 0L;
    }
}
