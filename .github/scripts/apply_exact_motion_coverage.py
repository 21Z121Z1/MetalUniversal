from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


def write_new(path: str, content: str) -> None:
    p = Path(path)
    if p.exists():
        raise SystemExit(f"refusing to overwrite existing file: {path}")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)


# Pure object-level exact-coverage transaction. It is deliberately separate from
# MetalFxMotionEligibility so the latter cannot accidentally infer correctness from a class name.
write_new(
    "src/main/java/com/metallum/client/metal/render/MetalExactMotionCoverage.java",
    r'''package com.metallum.client.metal.render;

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
'''
)

# History exposes only a count guarded by the same strict whole-object manifest predicate.
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalPreviousVertexHistory.java",
    """    static boolean objectManifestMatches(final ObjectKey object) {\n        int currentCount = CURRENT_DRAW_COUNTS.getOrDefault(object, 0);\n""",
    """    static int matchingManifestDrawCount(final long objectId, final long generation) {\n        ObjectKey object = new ObjectKey(objectId, generation);\n        return objectManifestMatches(object) ? CURRENT_DRAW_COUNTS.getOrDefault(object, 0) : -1;\n    }\n\n    static boolean objectManifestMatches(final ObjectKey object) {\n        int currentCount = CURRENT_DRAW_COUNTS.getOrDefault(object, 0);\n"""
)

# Capture owns the runtime proof. Unsupported exact-required pipelines are recorded before any
# fallback replay can make the frame look superficially complete.
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java",
    """        if (!value) {\n            clearFrameState();\n        }\n""",
    """        if (!value) {\n            clearFrameState();\n            MetalExactMotionCoverage.reset();\n        }\n"""
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java",
    """        clearFrameState();\n    }\n\n    private static void clearFrameState() {\n""",
    """        clearFrameState();\n        MetalExactMotionCoverage.beginFrame();\n    }\n\n    private static void clearFrameState() {\n"""
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java",
    """    public static boolean hasPreviousState(final Object state) {\n        Sample sample = enabled && state != null ? STATES.get(state) : null;\n        return sample != null && sample.hasPrevious();\n    }\n\n""",
    """    public static boolean hasPreviousState(final Object state) {\n        Sample sample = enabled && state != null ? STATES.get(state) : null;\n        return sample != null && sample.hasPrevious();\n    }\n\n    /** Marks the actual submitted entity object as requiring exact staged previous positions. */\n    public static void requireExactState(final Object state) {\n        Sample sample = enabled && state != null ? STATES.get(state) : null;\n        if (sample != null) {\n            MetalExactMotionCoverage.require(sample);\n        }\n    }\n\n    /** Marks auxiliary geometry which cannot yet be isolated into an exact per-owner staged draw. */\n    public static void rejectCurrentExactAuxiliary(final String reason) {\n        if (enabled) {\n            MetalExactMotionCoverage.fail(ENTITY_SUBMISSION.get(), reason);\n        }\n    }\n\n    /** Final source-frame proof consumed by frame-interpolator admission. */\n    public static boolean exactCoverageComplete() {\n        return !enabled || MetalExactMotionCoverage.complete();\n    }\n\n"""
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java",
    """        lastVertexShader = pipeline.getVertexShader().toString();\n        boolean matched = MetalEntityMotionPipeline.isSplittableVertexShader(pipeline);\n        if (matched) {\n            splitChecksMatched++;\n        }\n        return matched;\n""",
    """        lastVertexShader = pipeline.getVertexShader().toString();\n        boolean matched = MetalEntityMotionPipeline.isSplittableVertexShader(pipeline);\n        if (MetalExactMotionCoverage.required(sample)\n                && !MetalEntityMotionPipeline.supportsPreviousPositions(pipeline)) {\n            MetalExactMotionCoverage.fail(\n                    sample,\n                    \"unsupported-exact-pipeline:\" + pipeline.getVertexShader()\n            );\n        }\n        if (matched) {\n            splitChecksMatched++;\n        }\n        return matched;\n"""
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java",
    """    static Matrix4f objectCurrentToPrevious(final Sample sample) {\n""",
    """    static void recordExactReplayPlanned(final MetalPreviousVertexHistory.DrawToken token) {\n        if (enabled) {\n            MetalExactMotionCoverage.recordExactPlan(token);\n        }\n    }\n\n    static Matrix4f objectCurrentToPrevious(final Sample sample) {\n"""
)

# A plan counts only after every range/manifest/finite-position validation has succeeded.
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalPreviousVertexReplay.java",
    """        return new Plan(currentVertexBuffer, previousPositions, 0);\n""",
    """        MetalEntityMotionCapture.recordExactReplayPlanned(token);\n        return new Plan(currentVertexBuffer, previousPositions, 0);\n"""
)

# Auxiliary submit constructors run while EntityRenderDispatcher.submit still owns ENTITY_SUBMISSION.
# Leash/name-tag/text can later reactivate that exact owner per submit. Flame and shadow currently
# allocate a shared builder before iterating submits, so they must fail closed until their batching is
# deliberately split rather than pretending the shared draw belongs to one entity.
write_new(
    "src/main/java/com/metallum/mixin/render/FlameFeatureSubmitMetalFxMixin.java",
    r'''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlameFeatureRenderer.Submit.class)
public abstract class FlameFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$observeSharedFlameDraw(
            final PoseStack.Pose pose,
            final EntityRenderState entityRenderState,
            final Quaternionf rotation,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.rejectCurrentExactAuxiliary("flame-shared-staged-draw");
    }
}
'''
)
write_new(
    "src/main/java/com/metallum/mixin/render/ShadowFeatureSubmitMetalFxMixin.java",
    r'''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ShadowFeatureRenderer.Submit.class)
public abstract class ShadowFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$observeSharedShadowDraw(
            final Matrix4fc pose,
            final float radius,
            final List<EntityRenderState.ShadowPiece> pieces,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.rejectCurrentExactAuxiliary("shadow-shared-staged-draw");
    }
}
'''
)
write_new(
    "src/main/java/com/metallum/mixin/render/LeashFeatureSubmitMetalFxMixin.java",
    r'''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.LeashFeatureRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LeashFeatureRenderer.Submit.class)
public abstract class LeashFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureEntityOwner(
            final Matrix4f pose,
            final EntityRenderState.LeashState leashState,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(this);
    }
}
'''
)
write_new(
    "src/main/java/com/metallum/mixin/render/NameTagFeatureSubmitMetalFxMixin.java",
    r'''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NameTagFeatureRenderer.Submit.class)
public abstract class NameTagFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureEntityOwner(
            final Matrix4fc pose,
            final float x,
            final float y,
            final Component text,
            final int lightCoords,
            final int color,
            final int backgroundColor,
            final Font.DisplayMode displayMode,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(this);
    }
}
'''
)
write_new(
    "src/main/java/com/metallum/mixin/render/TextFeatureSubmitMetalFxMixin.java",
    r'''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextFeatureRenderer.Submit.class)
public abstract class TextFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureEntityOwner(
            final Matrix4fc pose,
            final float x,
            final float y,
            final FormattedCharSequence string,
            final boolean dropShadow,
            final Font.DisplayMode displayMode,
            final int lightCoords,
            final int color,
            final int backgroundColor,
            final int outlineColor,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(this);
    }
}
'''
)

# Renderer loops where getVertexBuilder occurs inside the per-submit body can safely reuse the
# proven activateBuildSampleOnAccess pattern.
for renderer in ("LeashFeatureRenderer", "NameTagFeatureRenderer", "TextFeatureRenderer"):
    write_new(
        f"src/main/java/com/metallum/mixin/render/{renderer}MetalFxMixin.java",
        f'''package com.metallum.mixin.render;\n\nimport com.metallum.client.metal.render.MetalEntityMotionCapture;\nimport net.minecraft.client.renderer.feature.FeatureFrameContext;\nimport net.minecraft.client.renderer.feature.{renderer};\nimport org.spongepowered.asm.mixin.Mixin;\nimport org.spongepowered.asm.mixin.injection.At;\nimport org.spongepowered.asm.mixin.injection.Inject;\nimport org.spongepowered.asm.mixin.injection.ModifyVariable;\nimport org.spongepowered.asm.mixin.injection.callback.CallbackInfo;\n\nimport java.util.List;\n\n@Mixin({renderer}.class)\npublic abstract class {renderer}MetalFxMixin {{\n    @ModifyVariable(method = "buildGroup", at = @At("HEAD"), argsOnly = true)\n    private List<{renderer}.Submit> metallum$activateMotionOwner(\n            final List<{renderer}.Submit> submits\n    ) {{\n        return MetalEntityMotionCapture.activateBuildSampleOnAccess(submits);\n    }}\n\n    @Inject(method = "buildGroup", at = @At("RETURN"))\n    private void metallum$clearMotionOwner(\n            final FeatureFrameContext context,\n            final List<{renderer}.Submit> submits,\n            final CallbackInfo ci\n    ) {{\n        MetalEntityMotionCapture.endModelBuild();\n    }}\n}}\n'''
    )

# Register new mixins immediately after the existing block-model capture group.
replace_once(
    "src/main/resources/metallum.mixins.json",
    '''    "render.BlockModelFeatureRendererMetalFxMixin",\n''',
    '''    "render.BlockModelFeatureRendererMetalFxMixin",\n    "render.FlameFeatureSubmitMetalFxMixin",\n    "render.ShadowFeatureSubmitMetalFxMixin",\n    "render.LeashFeatureSubmitMetalFxMixin",\n    "render.LeashFeatureRendererMetalFxMixin",\n    "render.NameTagFeatureSubmitMetalFxMixin",\n    "render.NameTagFeatureRendererMetalFxMixin",\n    "render.TextFeatureSubmitMetalFxMixin",\n    "render.TextFeatureRendererMetalFxMixin",\n'''
)

# Package-private tests exercise the transaction without constructing Minecraft render-state classes.
write_new(
    "src/test/java/com/metallum/client/metal/render/MetalExactMotionCoverageTest.java",
    r'''package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalExactMotionCoverageTest {
    @AfterEach
    void reset() {
        MetalExactMotionCoverage.reset();
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.reset();
    }

    @Test
    void noRequiredObjectIsComplete() {
        MetalExactMotionCoverage.beginFrame();
        assertTrue(MetalExactMotionCoverage.complete());
    }

    @Test
    void requiredObjectNeedsWholeManifestAndEveryExactPlan() {
        RenderPipeline pipeline = pipeline("exact_coverage");
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(17L, 4L, new Matrix4f(), null);
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        MetalPreviousVertexHistory.Signature signature = new MetalPreviousVertexHistory.Signature(
                pipeline.getLocation().toString(), format.getElements(), format.getVertexSize(),
                PrimitiveTopology.TRIANGLES, 1, 3
        );

        // Previous successful source frame.
        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.DrawToken previous = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(previous, signature, new float[] {0.0F, 0.0F, 0.0F});
        MetalPreviousVertexHistory.commitSubmittedFrame();

        // Current frame has a matching manifest but is incomplete until its exact replay is planned.
        MetalPreviousVertexHistory.beginFrame();
        MetalExactMotionCoverage.beginFrame();
        MetalExactMotionCoverage.require(sample);
        MetalPreviousVertexHistory.DrawToken current = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(current, signature, new float[] {1.0F, 0.0F, 0.0F});
        assertFalse(MetalExactMotionCoverage.complete());
        MetalExactMotionCoverage.recordExactPlan(current);
        assertTrue(MetalExactMotionCoverage.complete());
    }

    @Test
    void unsupportedAuxiliaryFailsClosedEvenWithMatchingHistory() {
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(21L, 8L, new Matrix4f(), null);
        MetalPreviousVertexHistory.beginFrame();
        MetalExactMotionCoverage.beginFrame();
        MetalExactMotionCoverage.require(sample);
        MetalExactMotionCoverage.fail(sample, "unsupported-auxiliary");
        assertFalse(MetalExactMotionCoverage.complete());
    }

    private static RenderPipeline pipeline(final String name) {
        Identifier id = Identifier.fromNamespaceAndPath("metallum", name);
        return RenderPipeline.builder()
                .withLocation(id)
                .withVertexShader(id)
                .withFragmentShader(id)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, VertexFormat.builder(0)
                        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
                        .build())
                .build();
    }
}
'''
)

print("exact motion coverage transaction patch prepared")
