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


display = "src/main/java/com/metallum/client/metal/render/MetalDisplayMotionSafety.java"
replace_once(
    display,
    "import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;\n",
    "import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;\n"
    "import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;\n"
)
replace_once(
    display,
    "import net.minecraft.world.level.block.state.BlockState;\n",
    "import net.minecraft.world.level.block.state.BlockState;\n"
    "import net.minecraft.network.chat.Component;\n"
)
replace_once(
    display,
    ''' * <p>Special renderers and text never enter the ordinary staged replay, so they\n * remain fail-closed.</p>\n''',
    ''' * <p>Special block/item renderers remain fail-closed. Text displays are admitted only when\n * their text/layout identity is continuous and the exact staged-position transaction proves every\n * text/background draw against the previous successfully submitted source frame.</p>\n'''
)
replace_once(
    display,
    '''        if (entity instanceof Display.ItemDisplay display && state instanceof ItemDisplayEntityRenderState itemState) {\n            captureItem(display, itemState);\n        }\n''',
    '''        if (entity instanceof Display.ItemDisplay display && state instanceof ItemDisplayEntityRenderState itemState) {\n            captureItem(display, itemState);\n            return;\n        }\n        if (entity instanceof Display.TextDisplay display && state instanceof TextDisplayEntityRenderState textState) {\n            captureText(display, textState);\n        }\n'''
)
replace_once(
    display,
    '''        if (state instanceof ItemDisplayEntityRenderState itemState) {\n            MetalItemModelIdentityAccess access = (MetalItemModelIdentityAccess) (Object) itemState.item;\n            return !itemState.item.isAnimated() && !access.metallum$hasSpecialLayer();\n        }\n        return false;\n    }\n\n    static ItemGeometry itemGeometry(final ItemDisplayContext context, final List<Object> identity) {\n''',
    '''        if (state instanceof ItemDisplayEntityRenderState itemState) {\n            MetalItemModelIdentityAccess access = (MetalItemModelIdentityAccess) (Object) itemState.item;\n            return !itemState.item.isAnimated() && !access.metallum$hasSpecialLayer();\n        }\n        if (state instanceof TextDisplayEntityRenderState textState) {\n            return textState.cachedInfo != null;\n        }\n        return false;\n    }\n\n    /** Text displays have no safe root-only fallback: every submitted glyph/background draw is exact. */\n    static boolean requiresExactPreviousPositions(final DisplayEntityRenderState state) {\n        return state instanceof TextDisplayEntityRenderState textState\n                && textState.hasSubState()\n                && textState.cachedInfo != null;\n    }\n\n    static ItemGeometry itemGeometry(final ItemDisplayContext context, final List<Object> identity) {\n'''
)
replace_once(
    display,
    '''    private static void captureItem(final Display.ItemDisplay display, final ItemDisplayEntityRenderState state) {\n        Display.ItemDisplay.ItemRenderState renderState = display.itemRenderState();\n        MetalItemModelIdentityAccess access = (MetalItemModelIdentityAccess) (Object) state.item;\n        if (renderState == null || state.item.isAnimated() || access.metallum$hasSpecialLayer()) {\n            LAST_GEOMETRY.remove(display);\n            CURRENT_CONTINUITY.put(state, false);\n            return;\n        }\n\n        ItemGeometry current = itemGeometry(renderState.itemTransform(), access.metallum$modelIdentity());\n        if (current == null) {\n            LAST_GEOMETRY.remove(display);\n            CURRENT_CONTINUITY.put(state, false);\n            return;\n        }\n        Object previous = LAST_GEOMETRY.put(display, current);\n        CURRENT_CONTINUITY.put(state, Objects.equals(previous, current));\n    }\n\n    record ItemGeometry(ItemDisplayContext displayContext, List<Object> modelIdentity) {\n    }\n}\n''',
    '''    private static void captureItem(final Display.ItemDisplay display, final ItemDisplayEntityRenderState state) {\n        Display.ItemDisplay.ItemRenderState renderState = display.itemRenderState();\n        MetalItemModelIdentityAccess access = (MetalItemModelIdentityAccess) (Object) state.item;\n        if (renderState == null || state.item.isAnimated() || access.metallum$hasSpecialLayer()) {\n            LAST_GEOMETRY.remove(display);\n            CURRENT_CONTINUITY.put(state, false);\n            return;\n        }\n\n        ItemGeometry current = itemGeometry(renderState.itemTransform(), access.metallum$modelIdentity());\n        if (current == null) {\n            LAST_GEOMETRY.remove(display);\n            CURRENT_CONTINUITY.put(state, false);\n            return;\n        }\n        Object previous = LAST_GEOMETRY.put(display, current);\n        CURRENT_CONTINUITY.put(state, Objects.equals(previous, current));\n    }\n\n    private static void captureText(final Display.TextDisplay display, final TextDisplayEntityRenderState state) {\n        Display.TextDisplay.TextRenderState renderState = state.textRenderState;\n        if (renderState == null || state.cachedInfo == null) {\n            LAST_GEOMETRY.remove(display);\n            CURRENT_CONTINUITY.put(state, false);\n            return;\n        }\n        TextGeometry current = textGeometry(renderState.text(), renderState.lineWidth(), renderState.flags());\n        Object previous = LAST_GEOMETRY.put(display, current);\n        CURRENT_CONTINUITY.put(state, Objects.equals(previous, current));\n    }\n\n    static TextGeometry textGeometry(final Component text, final int lineWidth, final byte flags) {\n        return new TextGeometry(text, lineWidth, flags);\n    }\n\n    record ItemGeometry(ItemDisplayContext displayContext, List<Object> modelIdentity) {\n    }\n\n    /**\n     * Geometry identity only. Text opacity and background-color interpolators intentionally do not\n     * participate: they change shading, while zero/nonzero background topology changes are caught by\n     * the exact whole-object draw manifest itself.\n     */\n    record TextGeometry(Component text, int lineWidth, byte flags) {\n    }\n}\n'''
)

manager = "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
replace_once(
    manager,
    '''        int incompleteReason = MetalFxMotionEligibility.incompleteEntityReason(state);\n        if (incompleteReason != 0) {\n            manager.motionEligibility.reject(incompleteReason);\n            return;\n        }\n        if (!MetalEntityMotionCapture.hasPreviousState(state)) {\n''',
    '''        int incompleteReason = MetalFxMotionEligibility.incompleteEntityReason(state);\n        if (incompleteReason != 0) {\n            manager.motionEligibility.reject(incompleteReason);\n            return;\n        }\n        if (state instanceof net.minecraft.client.renderer.entity.state.DisplayEntityRenderState displayState\n                && MetalDisplayMotionSafety.requiresExactPreviousPositions(displayState)) {\n            // TextDisplay submits both TextFeature and CustomGeometry (background) draws. Class-level\n            // admission is therefore only a candidate; the whole per-object staged manifest must\n            // match and every draw must actually encode exact previous positions before interpolation.\n            MetalEntityMotionCapture.requireExactState(state);\n        }\n        if (!MetalEntityMotionCapture.hasPreviousState(state)) {\n'''
)

write_new(
    "src/main/java/com/metallum/mixin/render/CustomFeatureSubmitMetalFxMixin.java",
    r'''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the submitting entity owner through hand-written custom geometry.
 *
 * Minecraft 26.2 constructs CustomFeatureRenderer.Submit with poseStack.last().copy(), so this
 * Pose is unique to the submit and is available again at CustomGeometryRenderer.render. Using it as
 * the carrier avoids depending on compiler locals or grouping order.
 */
@Mixin(CustomFeatureRenderer.Submit.class)
public abstract class CustomFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void metallum$captureEntityOwner(
            final PoseStack.Pose pose,
            final RenderType renderType,
            final SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(pose);
    }
}
'''
)

write_new(
    "src/main/java/com/metallum/mixin/render/CustomFeatureRendererMetalFxMixin.java",
    r'''package com.metallum.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Activates the exact owner only for the custom-geometry callback that emits this submit's vertices. */
@Mixin(CustomFeatureRenderer.class)
public abstract class CustomFeatureRendererMetalFxMixin {
    @WrapOperation(
            method = "buildGroup",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"
            )
    )
    private void metallum$bracketCustomGeometry(
            final SubmitNodeCollector.CustomGeometryRenderer renderer,
            final PoseStack.Pose pose,
            final VertexConsumer buffer,
            final Operation<Void> original
    ) {
        MetalEntityMotionCapture.beginModelBuild(pose);
        try {
            original.call(renderer, pose, buffer);
        } finally {
            MetalEntityMotionCapture.endModelBuild();
        }
    }
}
'''
)

replace_once(
    "src/main/resources/metallum.mixins.json",
    '''    "render.BlockModelFeatureRendererMetalFxMixin",\n''',
    '''    "render.BlockModelFeatureRendererMetalFxMixin",\n    "render.CustomFeatureSubmitMetalFxMixin",\n    "render.CustomFeatureRendererMetalFxMixin",\n'''
)

write_new(
    "src/test/java/com/metallum/client/metal/render/MetalTextDisplayMotionSafetyTest.java",
    r'''package com.metallum.client.metal.render;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class MetalTextDisplayMotionSafetyTest {
    @Test
    void geometryIdentityTracksTextLayoutAndFlags() {
        Component text = Component.literal("motion");
        MetalDisplayMotionSafety.TextGeometry baseline =
                MetalDisplayMotionSafety.textGeometry(text, 96, (byte) 0x01);
        assertEquals(baseline, MetalDisplayMotionSafety.textGeometry(text, 96, (byte) 0x01));
        assertNotEquals(baseline, MetalDisplayMotionSafety.textGeometry(Component.literal("changed"), 96, (byte) 0x01));
        assertNotEquals(baseline, MetalDisplayMotionSafety.textGeometry(text, 97, (byte) 0x01));
        assertNotEquals(baseline, MetalDisplayMotionSafety.textGeometry(text, 96, (byte) 0x03));
    }
}
'''
)

write_new(
    "src/test/java/com/metallum/client/metal/render/MetalTextDisplayExactCoverageTest.java",
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

final class MetalTextDisplayExactCoverageTest {
    @AfterEach
    void reset() {
        MetalExactMotionCoverage.reset();
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.reset();
    }

    @Test
    void backgroundAndGlyphDrawsMustBothEncodeExactly() {
        RenderPipeline background = pipeline("text_display_background");
        RenderPipeline glyphs = pipeline("text_display_glyphs");
        MetalEntityMotionCapture.Sample sample =
                new MetalEntityMotionCapture.Sample(91L, 3L, new Matrix4f(), new Matrix4f());

        MetalPreviousVertexHistory.beginFrame();
        stage(sample, background, new float[] {-1F, -1F, 0F});
        stage(sample, glyphs, new float[] {0F, 0F, 0F});
        MetalPreviousVertexHistory.commitSubmittedFrame();

        MetalPreviousVertexHistory.beginFrame();
        MetalExactMotionCoverage.beginFrame();
        MetalExactMotionCoverage.require(sample);
        MetalPreviousVertexHistory.DrawToken currentBackground =
                stage(sample, background, new float[] {-1F, -1F, 0F});
        MetalPreviousVertexHistory.DrawToken currentGlyphs =
                stage(sample, glyphs, new float[] {1F, 0F, 0F});

        assertFalse(MetalExactMotionCoverage.complete());
        MetalExactMotionCoverage.recordExactEncoded(currentBackground);
        assertFalse(MetalExactMotionCoverage.complete(), "glyph draw is still missing exact motion");
        MetalExactMotionCoverage.recordExactEncoded(currentGlyphs);
        assertTrue(MetalExactMotionCoverage.complete());
    }

    private static MetalPreviousVertexHistory.DrawToken stage(
            final MetalEntityMotionCapture.Sample sample,
            final RenderPipeline pipeline,
            final float[] positions
    ) {
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        MetalPreviousVertexHistory.DrawToken token = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(
                token,
                new MetalPreviousVertexHistory.Signature(
                        pipeline.getLocation().toString(),
                        format.getElements(),
                        format.getVertexSize(),
                        PrimitiveTopology.TRIANGLES,
                        positions.length / 3,
                        3
                ),
                positions
        );
        return token;
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

print("text display exact motion patch prepared")
