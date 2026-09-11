package com.metallum.client.metal.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
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
 *
 * <p>History lifetime is content-sensitive. A hand may reuse previous staged vertices only when
 * the complete ItemStack still matches the previous successfully submitted source frame. Item,
 * count or component changes therefore break continuity and advance the synthetic generation.
 * Pending lifetime changes are promoted only by commitSubmittedFrame(), never by a discarded
 * source frame.</p>
 */
public final class MetalSyntheticExactMotion {
    private static final long MAIN_HAND_OBJECT_ID = 0x4D46584D41494EL; // ASCII "MFXMAIN".
    private static final long OFF_HAND_OBJECT_ID = 0x4D46584F464648L;  // ASCII "MFXOFFH".
    // Real object lifetime generations are positive. Negative generations are reserved for
    // synthetic staged-only owners.
    private static final long MAIN_HAND_INITIAL_GENERATION = -0x4D41494EL;
    private static final long OFF_HAND_INITIAL_GENERATION = -0x4F464648L;
    private static final Object MAIN_HAND_STATE = new Object();
    private static final Object OFF_HAND_STATE = new Object();
    private static final ThreadLocal<Boolean> FIRST_PERSON_ACTIVE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static boolean frameOpen;
    private static @Nullable ItemStack previousMainHandStack;
    private static @Nullable ItemStack previousOffHandStack;
    private static long committedMainHandGeneration = MAIN_HAND_INITIAL_GENERATION;
    private static long committedOffHandGeneration = OFF_HAND_INITIAL_GENERATION;
    private static boolean pendingMainHand;
    private static boolean pendingOffHand;
    private static @Nullable ItemStack pendingMainHandStack;
    private static @Nullable ItemStack pendingOffHandStack;
    private static long pendingMainHandGeneration;
    private static long pendingOffHandGeneration;

    private MetalSyntheticExactMotion() {
    }

    static void beginFrame() {
        pendingMainHand = false;
        pendingOffHand = false;
        pendingMainHandStack = null;
        pendingOffHandStack = null;
        FIRST_PERSON_ACTIVE.remove();
        frameOpen = true;
    }

    /**
     * Begins one non-scoping first-person submission as an exact staged object.
     *
     * @return the sample used for the hand, or {@code null} when no safe transaction can be opened
     */
    public static MetalEntityMotionCapture.@Nullable Sample beginFirstPerson(
            final InteractionHand hand,
            final ItemStack itemStack
    ) {
        // Keep Frame Generation fail-closed until the native compositor has a first-person-specific
        // exact-motion validity plane. The exact history built here is a prerequisite, not evidence
        // that the final MetalFX motion texture is already safe for moving hand pixels.
        MetalFxManager.observeFirstPersonMotion();
        if (!frameOpen || hand == null || itemStack == null || Boolean.TRUE.equals(FIRST_PERSON_ACTIVE.get())) {
            MetalFxManager.observeFirstPersonMotion();
            return null;
        }

        final boolean main = hand == InteractionHand.MAIN_HAND;
        final Object state = main ? MAIN_HAND_STATE : OFF_HAND_STATE;
        final long objectId = main ? MAIN_HAND_OBJECT_ID : OFF_HAND_OBJECT_ID;
        final ItemStack snapshot = itemStack.copy();
        final ItemStack previousStack = main ? previousMainHandStack : previousOffHandStack;
        final long committedGeneration = main
                ? committedMainHandGeneration
                : committedOffHandGeneration;
        final boolean alreadyPending = main ? pendingMainHand : pendingOffHand;
        final ItemStack existingPending = main ? pendingMainHandStack : pendingOffHandStack;

        if (alreadyPending && (existingPending == null || !ItemStack.matches(existingPending, snapshot))) {
            // Two unrelated logical stacks must never share one exact staged owner in one source
            // frame. This is an unexpected renderer contract change, so reject the frame pair.
            MetalFxManager.observeFirstPersonMotion();
            return null;
        }

        final boolean hasPrevious = previousStack != null && ItemStack.matches(previousStack, snapshot);
        final long generation;
        if (alreadyPending) {
            generation = main ? pendingMainHandGeneration : pendingOffHandGeneration;
        } else if (hasPrevious || previousStack == null) {
            generation = committedGeneration;
        } else {
            generation = nextGeneration(committedGeneration);
        }

        if (main) {
            pendingMainHand = true;
            pendingMainHandStack = snapshot;
            pendingMainHandGeneration = generation;
        } else {
            pendingOffHand = true;
            pendingOffHandStack = snapshot;
            pendingOffHandGeneration = generation;
        }

        Matrix4f identity = new Matrix4f();
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(
                objectId,
                generation,
                identity,
                hasPrevious && generation == committedGeneration ? identity : null,
                FrameSynthesisContract.ProducerDomain.FIRST_PERSON
        );
        MetalEntityMotionCapture.attachState(state, sample);
        MetalEntityMotionCapture.requireExactState(state);
        MetalFxManager.markExactFirstPersonProducerCandidate(sample);
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
        if (pendingMainHand) {
            previousMainHandStack = pendingMainHandStack;
            committedMainHandGeneration = pendingMainHandGeneration;
        } else {
            previousMainHandStack = null;
        }
        if (pendingOffHand) {
            previousOffHandStack = pendingOffHandStack;
            committedOffHandGeneration = pendingOffHandGeneration;
        } else {
            previousOffHandStack = null;
        }
        pendingMainHand = false;
        pendingOffHand = false;
        pendingMainHandStack = null;
        pendingOffHandStack = null;
        FIRST_PERSON_ACTIVE.remove();
        frameOpen = false;
    }

    static void discardFrame() {
        pendingMainHand = false;
        pendingOffHand = false;
        pendingMainHandStack = null;
        pendingOffHandStack = null;
        FIRST_PERSON_ACTIVE.remove();
        frameOpen = false;
    }

    static void reset() {
        boolean wasOpen = frameOpen;
        previousMainHandStack = null;
        previousOffHandStack = null;
        committedMainHandGeneration = MAIN_HAND_INITIAL_GENERATION;
        committedOffHandGeneration = OFF_HAND_INITIAL_GENERATION;
        pendingMainHand = false;
        pendingOffHand = false;
        pendingMainHandStack = null;
        pendingOffHandStack = null;
        FIRST_PERSON_ACTIVE.remove();
        frameOpen = wasOpen;
    }

    private static long nextGeneration(final long generation) {
        if (generation == Long.MIN_VALUE) {
            MetalFxManager.observeFirstPersonMotion();
            return Long.MIN_VALUE;
        }
        return generation - 1L;
    }
}
