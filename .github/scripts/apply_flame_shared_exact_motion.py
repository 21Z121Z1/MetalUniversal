from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one anchor in {path!r}, found {count}")
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

    static @Nullable MetalEntityMotionCapture.Sample beginFlameBatch(final List<Member> members) {
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
    '''    /**\n     * Activates one synthetic exact owner for Minecraft 26.2's complete Flame shared builder.\n     * Every Submit must resolve to the positive-lifetime owner captured at entity submission.\n     * Per-member emitted vertex spans prevent equal-and-opposite topology changes from preserving\n     * an unsafe aggregate ordinal mapping.\n     */\n    public static void beginSharedFlameBuild(final List<FlameFeatureRenderer.Submit> submits) {\n        if (!enabled) {\n            return;\n        }\n        MODEL_BUILD.remove();\n        if (submits == null || submits.isEmpty()) {\n            return;\n        }\n\n        java.util.ArrayList<MetalSharedBatchMotion.Member> members = new java.util.ArrayList<>(submits.size());\n        for (FlameFeatureRenderer.Submit submit : submits) {\n            Sample owner = submit == null ? null : SUBMITS.remove(submit);\n            int vertexSpan = submit == null\n                    ? -1\n                    : MetalSharedBatchMotion.flameVertexSpan(\n                            submit.entityRenderState().boundingBoxWidth,\n                            submit.entityRenderState().boundingBoxHeight\n                    );\n            if (owner == null || owner.generation() <= 0L || vertexSpan <= 0) {\n                MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();\n                return;\n            }\n            members.add(new MetalSharedBatchMotion.Member(\n                    owner.objectId(), owner.generation(), vertexSpan\n            ));\n        }\n\n        Sample batch = MetalSharedBatchMotion.beginFlameBatch(members);\n        if (batch == null) {\n            MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();\n            return;\n        }\n        MODEL_BUILD.set(batch);\n        // First sight, membership/order changes and per-member span changes have no matching exact\n        // history. Marking the synthetic owner exact-required guarantees root-motion fallback can\n        // never make such a source frame eligible for MTLFXFrameInterpolator.\n        MetalExactMotionCoverage.require(batch);\n        modelBuildsMatched++;\n    }\n\n    public static void beginModelBuild(final Object submit) {\n        beginBuild(submit, false);\n    }\n'''
)

replace_once(
    "src/main/java/com/metallum/mixin/render/FlameFeatureSubmitMetalFxMixin.java",
    '''import com.metallum.client.metal.render.MetalFxManager;\n''',
    ''''''
)
replace_once(
    "src/main/java/com/metallum/mixin/render/FlameFeatureSubmitMetalFxMixin.java",
    '''        MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();\n        MetalEntityMotionCapture.rejectCurrentExactAuxiliary("flame-shared-staged-draw");\n''',
    '''        // This constructor runs inside the parent entity submission. Preserve that exact\n        // lifetime until FlameFeatureRenderer builds its one shared staged draw.\n        MetalEntityMotionCapture.captureModelSubmit(this);\n'''
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
    private static final MetalSharedBatchMotion.Member A4 = new MetalSharedBatchMotion.Member(10L, 1L, 4);
    private static final MetalSharedBatchMotion.Member A8 = new MetalSharedBatchMotion.Member(10L, 1L, 8);
    private static final MetalSharedBatchMotion.Member B4 = new MetalSharedBatchMotion.Member(20L, 2L, 4);
    private static final MetalSharedBatchMotion.Member B8 = new MetalSharedBatchMotion.Member(20L, 2L, 8);

    @AfterEach
    void reset() {
        MetalSharedBatchMotion.reset();
    }

    @Test
    void flameSpanMatchesPinnedMinecraftLoopSemantics() {
        // width 1 => scale 1.4. Height 1.4 starts at h=1 and emits 3 layers: 1,.55,.10.
        assertEquals(12, MetalSharedBatchMotion.flameVertexSpan(1.0F, 1.4F));
        assertEquals(4, MetalSharedBatchMotion.flameVertexSpan(1.0F, 0.1F));
        assertEquals(-1, MetalSharedBatchMotion.flameVertexSpan(0.0F, 1.0F));
        assertEquals(-1, MetalSharedBatchMotion.flameVertexSpan(Float.NaN, 1.0F));
        assertEquals(-1, MetalSharedBatchMotion.flameVertexSpan(1.0F, Float.POSITIVE_INFINITY));
    }

    @Test
    void sameSubmittedMembershipAndSpansReuseExactNegativeGeneration() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A4, B8));
        assertNotNull(first);
        assertTrue(first.generation() < 0L);
        assertFalse(first.hasPrevious());
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample second = MetalSharedBatchMotion.beginFlameBatch(List.of(A4, B8));
        assertNotNull(second);
        assertEquals(first.generation(), second.generation());
        assertTrue(second.hasPrevious());
    }

    @Test
    void equalTotalButRedistributedMemberSpansInvalidateOrdinalContinuity() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A4, B8));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        // Both frames have 12 aggregate vertices; the entity boundary moves from 4 to 8.
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample redistributed = MetalSharedBatchMotion.beginFlameBatch(List.of(A8, B4));
        assertNotNull(redistributed);
        assertNotEquals(first.generation(), redistributed.generation());
        assertFalse(redistributed.hasPrevious());
    }

    @Test
    void reorderedOrReplacedMembershipAllocatesFreshGeneration() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A4, B8));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample reordered = MetalSharedBatchMotion.beginFlameBatch(List.of(B8, A4));
        assertNotNull(reordered);
        assertNotEquals(first.generation(), reordered.generation());
        assertFalse(reordered.hasPrevious());
    }

    @Test
    void discardedFrameCannotAdvanceSubmittedMembership() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A4));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        assertNotNull(MetalSharedBatchMotion.beginFlameBatch(List.of(B4)));
        MetalSharedBatchMotion.discardFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample recovered = MetalSharedBatchMotion.beginFlameBatch(List.of(A4));
        assertNotNull(recovered);
        assertEquals(first.generation(), recovered.generation());
        assertTrue(recovered.hasPrevious());
    }

    @Test
    void successfulFrameWithoutFlameBreaksContinuity() {
        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample first = MetalSharedBatchMotion.beginFlameBatch(List.of(A4));
        assertNotNull(first);
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalSharedBatchMotion.commitSubmittedFrame();

        MetalSharedBatchMotion.beginFrame();
        MetalEntityMotionCapture.Sample returned = MetalSharedBatchMotion.beginFlameBatch(List.of(A4));
        assertNotNull(returned);
        assertNotEquals(first.generation(), returned.generation());
        assertFalse(returned.hasPrevious());
    }

    @Test
    void secondSharedFlameBatchOrInvalidMemberFailsClosed() {
        MetalSharedBatchMotion.beginFrame();
        assertNotNull(MetalSharedBatchMotion.beginFlameBatch(List.of(A4)));
        assertNull(MetalSharedBatchMotion.beginFlameBatch(List.of(A4)));

        MetalSharedBatchMotion.reset();
        MetalSharedBatchMotion.beginFrame();
        assertNull(MetalSharedBatchMotion.beginFlameBatch(List.of(
                new MetalSharedBatchMotion.Member(10L, -1L, 4)
        )));
        assertNull(MetalSharedBatchMotion.beginFlameBatch(List.of(
                new MetalSharedBatchMotion.Member(10L, 1L, 0)
        )));
    }
}
''')

print("Flame shared exact-motion v2 patch prepared")
