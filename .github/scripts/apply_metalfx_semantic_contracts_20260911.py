from pathlib import Path
import textwrap


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


# MetalFX Temporal: both current factory/rebuild descriptor sites must use
# framework auto exposure until Minecraft has an explicit, validated 1x1 R16Float
# exposure producer. Do not silently assume exposure == 1.0.
native = Path("src/main/native/MetallumNative.swift")
text = native.read_text()
old_exposure = "descriptor.isAutoExposureEnabled = false"
exposure_sites = text.count(old_exposure)
if exposure_sites != 2:
    raise SystemExit(f"expected exactly 2 disabled Temporal exposure descriptors, found {exposure_sites}")
text = text.replace(old_exposure, "descriptor.isAutoExposureEnabled = true")
if old_exposure in text:
    raise SystemExit("stale disabled Temporal exposure descriptor remains")
native.write_text(text)


Path("src/main/java/com/metallum/client/metal/render/MetalSyntheticExactMotion.java").write_text(textwrap.dedent('''\
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
 * <p>The lifetime is content-sensitive: exact history may be reused only when the complete
 * ItemStack identity (count, item and data components) matches the previous committed source
 * frame. A content transition gets a new generation. Pending transitions are promoted only by a
 * successful source-frame commit, so a discarded frame cannot poison the next frame's history.</p>
 */
public final class MetalSyntheticExactMotion {
    private static final long MAIN_HAND_OBJECT_ID = 0x4D46584D41494EL; // ASCII "MFXMAIN".
    private static final long OFF_HAND_OBJECT_ID = 0x4D46584F464648L;  // ASCII "MFXOFFH".
    // Positive generations belong to real world objects. Negative generations reserve a disjoint
    // namespace for synthetic staged-only owners.
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
            // Two unrelated logical stacks must never share one staged owner in a source frame.
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
            previousMainHandStack = pendingMainHandStack == null ? null : pendingMainHandStack.copy();
            committedMainHandGeneration = pendingMainHandGeneration;
        } else {
            previousMainHandStack = null;
        }
        if (pendingOffHand) {
            previousOffHandStack = pendingOffHandStack == null ? null : pendingOffHandStack.copy();
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
'''))

replace_once(
    "src/main/java/com/metallum/mixin/render/ItemInHandRendererMetalFxMixin.java",
    "MetalSyntheticExactMotion.beginFirstPerson(hand);",
    "MetalSyntheticExactMotion.beginFirstPerson(hand, itemStack);",
)

replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalFxManager.java",
    """    // True when this source frame submitted first-person geometry. The current\n    // hand path has no trusted previous local vertices for swing/bob/equip, so\n    // this observation is a hard Frame Generation admission veto. Temporal can\n    // still consume its reactive/history inputs.\n    private boolean firstPersonMotionObserved;\n""",
    """    // True only when first-person geometry could not enter the exact staged-history transaction\n    // (for example nested/unframed ownership or a mid-frame ItemStack lifetime change). Ordinary\n    // first-person swing/bob/equip uses exact previous staged vertices instead of this veto.\n    private boolean firstPersonMotionObserved;\n""",
)

replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalFxManager.java",
    """    /**\n     * Records first-person geometry for this source frame. Its swing/bob/equip\n     * pose has no trusted previous local-vertex stream yet, so this is a\n     * deliberate Frame Generation veto; Temporal remains enabled.\n     */\n""",
    """    /**\n     * Fail-closed fallback for first-person geometry that could not be assigned to the exact\n     * staged-history transaction. Ordinary hand rendering should not call this path.\n     */\n""",
)

Path("src/test/java/com/metallum/client/metal/render/MetalSyntheticExactMotionTest.java").write_text(textwrap.dedent('''\
package com.metallum.client.metal.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalSyntheticExactMotionTest {
    @AfterEach
    void reset() {
        MetalSyntheticExactMotion.reset();
        MetalEntityMotionCapture.beginFrame();
    }

    @Test
    void firstPersonHistoryAdvancesOnlyAfterSubmittedSourceFrame() {
        ItemStack stack = new ItemStack(Items.STONE);

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample first =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, stack);
        assertNotNull(first);
        assertFalse(first.hasPrevious());
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample second =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, stack.copy());
        assertNotNull(second);
        assertTrue(second.hasPrevious());
        assertEquals(first.generation(), second.generation());
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.discardFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample afterDiscard =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, stack.copy());
        assertNotNull(afterDiscard);
        assertTrue(afterDiscard.hasPrevious());
        assertEquals(first.generation(), afterDiscard.generation());
        MetalSyntheticExactMotion.endFirstPerson();
    }

    @Test
    void submittedFrameWithoutHandBreaksContinuity() {
        ItemStack stack = new ItemStack(Items.STONE);

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        assertNotNull(MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.OFF_HAND, stack));
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample returned =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.OFF_HAND, stack.copy());
        assertNotNull(returned);
        assertFalse(returned.hasPrevious());
        MetalSyntheticExactMotion.endFirstPerson();
    }

    @Test
    void itemTransitionGetsNewGenerationAndDiscardDoesNotAdvanceCommittedLifetime() {
        ItemStack stone = new ItemStack(Items.STONE);
        ItemStack dirt = new ItemStack(Items.DIRT);

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample stoneFirst =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, stone);
        assertNotNull(stoneFirst);
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample dirtDiscarded =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, dirt);
        assertNotNull(dirtDiscarded);
        assertFalse(dirtDiscarded.hasPrevious());
        assertNotEquals(stoneFirst.generation(), dirtDiscarded.generation());
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.discardFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample stoneAfterDiscard =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, stone.copy());
        assertNotNull(stoneAfterDiscard);
        assertTrue(stoneAfterDiscard.hasPrevious());
        assertEquals(stoneFirst.generation(), stoneAfterDiscard.generation());
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample dirtCommitted =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, dirt.copy());
        assertNotNull(dirtCommitted);
        assertFalse(dirtCommitted.hasPrevious());
        assertNotEquals(stoneAfterDiscard.generation(), dirtCommitted.generation());
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample dirtNext =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, dirt.copy());
        assertNotNull(dirtNext);
        assertTrue(dirtNext.hasPrevious());
        assertEquals(dirtCommitted.generation(), dirtNext.generation());
        MetalSyntheticExactMotion.endFirstPerson();
    }

    @Test
    void countChangeBreaksContinuityConservatively() {
        ItemStack one = new ItemStack(Items.STONE, 1);
        ItemStack two = new ItemStack(Items.STONE, 2);

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample first =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, one);
        assertNotNull(first);
        MetalSyntheticExactMotion.endFirstPerson();
        MetalSyntheticExactMotion.commitSubmittedFrame();

        MetalEntityMotionCapture.beginFrame();
        MetalSyntheticExactMotion.beginFrame();
        MetalEntityMotionCapture.Sample changed =
                MetalSyntheticExactMotion.beginFirstPerson(InteractionHand.MAIN_HAND, two);
        assertNotNull(changed);
        assertFalse(changed.hasPrevious());
        assertNotEquals(first.generation(), changed.generation());
        MetalSyntheticExactMotion.endFirstPerson();
    }
}
'''))

Path("src/test/java/com/metallum/client/metal/render/MetalFxTemporalExposureContractTest.java").write_text(textwrap.dedent('''\
package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class MetalFxTemporalExposureContractTest {
    @Test
    void everyTemporalDescriptorUsesAutoExposureUntilExplicitExposureTextureExists() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        assertFalse(source.contains("descriptor.isAutoExposureEnabled = false"));
        assertEquals(2, occurrences(source, "descriptor.isAutoExposureEnabled = true"));
    }

    private static int occurrences(final String source, final String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
'''))
