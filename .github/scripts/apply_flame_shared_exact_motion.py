from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if text.count(old) != 1:
        raise SystemExit(f"expected exactly one anchor in {path!r}, found {text.count(old)}")
    file.write_text(text.replace(old, new, 1))


shared = Path("src/main/java/com/metallum/client/metal/render/MetalSharedBatchMotion.java")
if shared.exists():
    raise SystemExit(f"refusing to overwrite existing {shared}")
shared.write_text('''package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Transactional identity for entity-owned geometry which Minecraft deliberately stages as one
 * shared draw. The shared batch uses negative generations, while ordinary entity/piston lifetimes
 * are allocated from MetalFxManager's positive nextEntityGeneration domain.
 *
 * <p>The membership signature is committed only with a successfully submitted source frame.
 * Geometry topology is deliberately not duplicated here: MetalPreviousVertexHistory already
 * compares the actual staged pipeline/format/topology/vertex/index manifest byte-for-byte at the
 * draw boundary. This class only proves that vertex ordinal N still belongs to the same ordered
 * entity lifetime before that exact staged-position history may be reused.</p>
 */
final class MetalSharedBatchMotion {
    static final long FLAME_OBJECT_ID = 0x4D46584C414D45L; // "MFXLAME"; generation is the domain separator.

    record Member(long objectId, long generation) {
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

    static @Nullable MetalEntityMotionCapture.Sample beginFlameBatch(final List<Member> members) {
        if (!frameOpen || flameBatchOpened || members == null || members.isEmpty()) {
            return null;
        }
        for (Member member : members) {
            if (member == null || member.generation() <= 0L) {
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
        // flame geometry must seed a fresh batch generation instead of reaching across the gap.
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
''')

replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalMotionStateStore.java",
    '''        frameOpen = true;\n        MetalPreviousVertexHistory.beginFrame();\n''',
    '''        frameOpen = true;\n        MetalPreviousVertexHistory.beginFrame();\n        MetalSharedBatchMotion.beginFrame();\n'''
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalMotionStateStore.java",
    '''        pending.clear();\n        MetalPreviousVertexHistory.commitSubmittedFrame();\n        frameOpen = false;\n''',
    '''        pending.clear();\n        MetalPreviousVertexHistory.commitSubmittedFrame();\n        MetalSharedBatchMotion.commitSubmittedFrame();\n        frameOpen = false;\n'''
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalMotionStateStore.java",
    '''        pending.clear();\n        MetalPreviousVertexHistory.discardFrame();\n        frameOpen = false;\n''',
    '''        pending.clear();\n        MetalPreviousVertexHistory.discardFrame();\n        MetalSharedBatchMotion.discardFrame();\n        frameOpen = false;\n'''
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalMotionStateStore.java",
    '''        pending.clear();\n        MetalPreviousVertexHistory.reset();\n        frameOpen = wasOpen;\n''',
    '''        pending.clear();\n        MetalPreviousVertexHistory.reset();\n        MetalSharedBatchMotion.reset();\n        frameOpen = wasOpen;\n'''
)

replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java",
    '''import net.minecraft.client.renderer.StagedVertexBuffer;\n''',
    '''import net.minecraft.client.renderer.StagedVertexBuffer;\nimport net.minecraft.client.renderer.feature.FlameFeatureRenderer;\n'''
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java",
    '''    public static void beginModelBuild(final Object submit) {\n        beginBuild(submit, false);\n    }\n''',
    '''    /**\n     * Activates one synthetic exact owner for Minecraft 26.2's complete Flame shared builder.\n     * Every Submit must still resolve to the exact entity lifetime captured during submission;\n     * otherwise the whole source frame stays real. The actual staged draw manifest provides the\n     * topology/vertex-count proof, so this method never duplicates FlameFeatureRenderer.prepare.\n     */\n    public static void beginSharedFlameBuild(final List<FlameFeatureRenderer.Submit> submits) {\n        if (!enabled) {\n            return;\n        }\n        MODEL_BUILD.remove();\n        if (submits == null || submits.isEmpty()) {\n            return;\n        }\n\n        java.util.ArrayList<MetalSharedBatchMotion.Member> members = new java.util.ArrayList<>(submits.size());\n        for (FlameFeatureRenderer.Submit submit : submits) {\n            Sample owner = submit == null ? null : SUBMITS.remove(submit);\n            if (owner == null || owner.generation() <= 0L) {\n                MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();\n                return;\n            }\n            members.add(new MetalSharedBatchMotion.Member(owner.objectId(), owner.generation()));\n        }\n\n        Sample batch = MetalSharedBatchMotion.beginFlameBatch(members);\n        if (batch == null) {\n            MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();\n            return;\n        }\n        MODEL_BUILD.set(batch);\n        MetalExactMotionCoverage.require(batch);\n        modelBuildsMatched++;\n    }\n\n    public static void beginModelBuild(final Object submit) {\n        beginBuild(submit, false);\n    }\n'''
)

replace_once(
    "src/main/java/com/metallum/mixin/render/FlameFeatureSubmitMetalFxMixin.java",
    '''import com.metallum.client.metal.render.MetalFxManager;\n''',
    ''''''
)
replace_once(
    "src/main/java/com/metallum/mixin/render/FlameFeatureSubmitMetalFxMixin.java",
    '''        MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();\n        MetalEntityMotionCapture.rejectCurrentExactAuxiliary("flame-shared-staged-draw");\n''',
    '''        // The constructor still runs inside the owning entity submission. Keep that lifetime\n        // association until FlameFeatureRenderer builds the one shared staged draw. Admission is\n        // decided there, after the complete ordered membership is known.\n        MetalEntityMotionCapture.captureModelSubmit(this);\n'''
)

flame_mixin = Path("src/main/java/com/metallum/mixin/render/FlameFeatureRendererMetalFxMixin.java")
if flame_mixin.exists():
    raise SystemExit(f"refusing to overwrite existing {flame_mixin}")
flame_mixin.write_text('''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Owns Minecraft 26.2's single Flame shared builder as one exact staged-motion batch. */
@Mixin(FlameFeatureRenderer.class)
public abstract class FlameFeatureRendererMetalFxMixin {
    @Inject(method = "buildGroup", at = @At("HEAD"))
    private void metallum$beginSharedFlameMotion(
            final FeatureFrameContext context,
            final List<FlameFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.beginSharedFlameBuild(submits);
    }

    @Inject(method = "buildGroup", at = @At("RETURN"))
    private void metallum$endSharedFlameMotion(
            final FeatureFrameContext context,
            final List<FlameFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
''')

replace_once(
    "src/main/resources/metallum.mixins.json",
    '''    "render.FlameFeatureSubmitMetalFxMixin",\n    "render.ShadowFeatureSubmitMetalFxMixin",\n''',
    '''    "render.FlameFeatureSubmitMetalFxMixin",\n    "render.FlameFeatureRendererMetalFxMixin",\n    "render.ShadowFeatureSubmitMetalFxMixin",\n'''
)

test = Path("src/test/java/com/metallum/client/metal/render/MetalSharedBatchMotionTest.java")
if test.exists():
    raise SystemExit(f"refusing to overwrite existing {test}")
test.write_text('''package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetalSharedBatchMotionTest {
    private static final MetalSharedBatchMotion.Member A = new MetalSharedBatchMotion.Member(10L, 1L);
    private static final MetalSharedBatchMotion.Member B = new MetalSharedBatchMotion.Member(20L, 2L);

    @AfterEach
    void reset() {
        MetalSharedBatchMotion.reset();
    }

    @Test
    void sameSubmittedMembershipReusesExactNegativeGeneration() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A, B));
        assertNotNull(first);
        assertTrue(first.generation() < 0L);
        assertFalse(first.hasPrevious());
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample second = MetalSharedBatchMotion.beginFlameBatch(List.of(A, B));
        assertNotNull(second);
        assertEquals(first.generation(), second.generation());
        assertTrue(second.hasPrevious());
    }

    @Test
    void reorderedOrReplacedMembershipAllocatesFreshGeneration() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A, B));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample reordered = MetalSharedBatchMotion.beginFlameBatch(List.of(B, A));
        assertNotNull(reordered);
        assertNotEquals(first.generation(), reordered.generation());
        assertFalse(reordered.hasPrevious());
    }

    @Test
    void discardedFrameCannotAdvanceSubmittedMembership() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample transientBatch = MetalSharedBatchMotion.beginFlameBatch(List.of(B));
        assertNotNull(transientBatch);
        MetalSharedBatchMotion.discardFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample recovered = MetalSharedBatchMotion.beginFlameBatch(List.of(A));
        assertNotNull(recovered);
        assertEquals(first.generation(), recovered.generation());
        assertTrue(recovered.hasPrevious());
    }

    @Test
    void successfulFrameWithoutFlameBreaksContinuity() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample returned = MetalSharedBatchMotion.beginFlameBatch(List.of(A));
        assertNotNull(returned);
        assertNotEquals(first.generation(), returned.generation());
        assertFalse(returned.hasPrevious());
    }

    @Test
    void aSecondSharedFlameBatchInOneFrameFailsClosed() {
        MetalSharedBatchMotion.beginFrame();
        assertNotNull(MetalSharedBatchMotion.beginFlameBatch(List.of(A)));
        assertNull(MetalSharedBatchMotion.beginFlameBatch(List.of(A)));
    }

    @Test
    void invalidParentGenerationCannotEnterSyntheticDomain() {
        MetalSharedBatchMotion.beginFrame();
        assertNull(MetalSharedBatchMotion.beginFlameBatch(List.of(
                new MetalSharedBatchMotion.Member(10L, -1L)
        )));
    }
}
''')

print("Flame shared exact-motion patch prepared")
