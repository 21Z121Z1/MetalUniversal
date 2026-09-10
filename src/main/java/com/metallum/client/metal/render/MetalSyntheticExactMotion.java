package com.metallum.client.metal.render;

import net.minecraft.world.InteractionHand;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * Source-frame-transactional identities for exact staged geometry which has no world entity owner.
 *
 * <p>First-person main/off-hand rendering is generated after its current swing/equip/use PoseStack
 * transforms are known, but outside EntityRenderDispatcher. The staged vertex stream is therefore
 * the authoritative motion source. Stable synthetic keys let MetalPreviousVertexHistory compare
 * that stream to the previous successfully submitted source frame without pretending that the
 * current hand transform is a rigid object-motion approximation.</p>
 */
public final class MetalSyntheticExactMotion {
    private static final long MAIN_HAND_OBJECT_ID = 0x4D46584D41494EL; // ASCII "MFXMAIN".
    private static final long OFF_HAND_OBJECT_ID = 0x4D46584F464648L;  // ASCII "MFXOFFH".
    // Real object lifetime generations are positive; fixed negative values are an explicit domain
    // separator for synthetic staged-only objects.
    private static final long MAIN_HAND_GENERATION = -0x4D41494EL;
    private static final long OFF_HAND_GENERATION = -0x4F464648L;
    private static final Object MAIN_HAND_STATE = new Object();
    private static final Object OFF_HAND_STATE = new Object();
    private static final ThreadLocal<Boolean> FIRST_PERSON_ACTIVE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static boolean frameOpen;
    private static boolean previousMainHand;
    private static boolean previousOffHand;
    private static boolean pendingMainHand;
    private static boolean pendingOffHand;

    private MetalSyntheticExactMotion() {
    }

    static void beginFrame() {
        pendingMainHand = false;
        pendingOffHand = false;
        FIRST_PERSON_ACTIVE.remove();
        frameOpen = true;
    }

    /**
     * Begins one non-scoping first-person submission as an exact staged object.
     *
     * @return the sample used for the hand, or {@code null} when no safe transaction can be opened
     */
    public static MetalEntityMotionCapture.@Nullable Sample beginFirstPerson(final InteractionHand hand) {
        // The current renderer does not prove previous vertices for the complete
        // swing/bob/equip pose. Keep Frame Generation fail-closed even if this
        // synthetic owner is invoked inside a future frame transaction.
        MetalFxManager.observeFirstPersonMotion();
        if (!frameOpen || hand == null || Boolean.TRUE.equals(FIRST_PERSON_ACTIVE.get())) {
            // Nested/unframed ownership would make capture attribution ambiguous. Keep the legacy
            // whole-frame rejection for this impossible/changed-source-contract case.
            MetalFxManager.observeFirstPersonMotion();
            return null;
        }

        boolean main = hand == InteractionHand.MAIN_HAND;
        Object state = main ? MAIN_HAND_STATE : OFF_HAND_STATE;
        long objectId = main ? MAIN_HAND_OBJECT_ID : OFF_HAND_OBJECT_ID;
        long generation = main ? MAIN_HAND_GENERATION : OFF_HAND_GENERATION;
        boolean hasPrevious = main ? previousMainHand : previousOffHand;
        if (main) {
            pendingMainHand = true;
        } else {
            pendingOffHand = true;
        }

        Matrix4f identity = new Matrix4f();
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(
                objectId,
                generation,
                identity,
                hasPrevious ? identity : null
        );
        MetalEntityMotionCapture.attachState(state, sample);
        MetalEntityMotionCapture.requireExactState(state);
        MetalEntityMotionCapture.beginEntitySubmission(state);
        FIRST_PERSON_ACTIVE.set(Boolean.TRUE);
        return sample;
    }

    public static void endFirstPerson() {
        if (!Boolean.TRUE.equals(FIRST_PERSON_ACTIVE.get())) {
            return;
        }
        MetalEntityMotionCapture.endEntitySubmission();
        FIRST_PERSON_ACTIVE.remove();
    }

    static void commitSubmittedFrame() {
        if (!frameOpen) {
            return;
        }
        previousMainHand = pendingMainHand;
        previousOffHand = pendingOffHand;
        pendingMainHand = false;
        pendingOffHand = false;
        FIRST_PERSON_ACTIVE.remove();
        frameOpen = false;
    }

    static void discardFrame() {
        pendingMainHand = false;
        pendingOffHand = false;
        FIRST_PERSON_ACTIVE.remove();
        frameOpen = false;
    }

    static void reset() {
        boolean wasOpen = frameOpen;
        previousMainHand = false;
        previousOffHand = false;
        pendingMainHand = false;
        pendingOffHand = false;
        FIRST_PERSON_ACTIVE.remove();
        frameOpen = wasOpen;
    }
}
