from pathlib import Path
import json


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, found {count}")
    p.write_text(text.replace(old, new, 1))


def write(path: str, content: str) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)


capture = "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java"
replace_once(
    capture,
    "import java.util.IdentityHashMap;\nimport java.util.Map;\n",
    "import java.util.AbstractList;\nimport java.util.IdentityHashMap;\nimport java.util.List;\nimport java.util.Map;\n",
)
replace_once(
    capture,
    "    public static void endModelBuild() {\n        if (enabled) {\n            MODEL_BUILD.remove();\n        }\n    }\n",
    "    /**\n"
    "     * Wraps a one-pass feature-submit list so each element activates its owning motion sample\n"
    "     * before the renderer asks RenderTypeFeatureRenderer for a staged draw. This is needed by\n"
    "     * BlockModelFeatureRenderer, whose buildGroup method has no per-submit helper analogous to\n"
    "     * ItemFeatureRenderer.prepareSubmit. The wrapper preserves order and delegates storage.\n"
    "     */\n"
    "    public static <T> List<T> activateBuildSampleOnAccess(final List<T> submits) {\n"
    "        if (!enabled || submits == null || submits.isEmpty()) {\n"
    "            return submits;\n"
    "        }\n"
    "        return new AbstractList<>() {\n"
    "            @Override\n"
    "            public T get(final int index) {\n"
    "                T submit = submits.get(index);\n"
    "                beginModelBuild(submit);\n"
    "                return submit;\n"
    "            }\n\n"
    "            @Override\n"
    "            public int size() {\n"
    "                return submits.size();\n"
    "            }\n"
    "        };\n"
    "    }\n\n"
    "    public static void endModelBuild() {\n        if (enabled) {\n            MODEL_BUILD.remove();\n        }\n    }\n",
)

submit_mixin = '''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Binds ordinary baked block-model submits to the entity that produced them.
 * Minecraft 26.2 creates this record inside SubmitNodeCollection.submitBlockModel while
 * EntityRenderDispatcher.submit still owns the corresponding entity motion sample.
 */
@Mixin(BlockModelFeatureRenderer.Submit.class)
public abstract class BlockModelFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureEntityOwner(
            final PoseStack.Pose pose,
            final RenderType renderType,
            final List<BlockStateModelPart> modelParts,
            final int[] tintLayers,
            final int lightCoords,
            final int overlayCoords,
            final int tintColor,
            final PoseStack.@Nullable Pose sheetedDecalPose,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(this);
    }
}
'''
write("src/main/java/com/metallum/mixin/render/BlockModelFeatureSubmitMetalFxMixin.java", submit_mixin)

renderer_mixin = '''package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Activates the exact entity sample for each BlockModelFeatureRenderer.Submit before
 * buildGroup calls getVertexBuilder. The source method is a single enhanced-for pass,
 * so list element access is the stable per-submit boundary without relying on compiler locals.
 */
@Mixin(BlockModelFeatureRenderer.class)
public abstract class BlockModelFeatureRendererMetalFxMixin {
    @ModifyVariable(method = "buildGroup", at = @At("HEAD"), argsOnly = true)
    private List<BlockModelFeatureRenderer.Submit> metallum$activateMotionOwner(
            final List<BlockModelFeatureRenderer.Submit> submits
    ) {
        return MetalEntityMotionCapture.activateBuildSampleOnAccess(submits);
    }

    @Inject(method = "buildGroup", at = @At("RETURN"))
    private void metallum$clearMotionOwner(
            final FeatureFrameContext context,
            final List<BlockModelFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
'''
write("src/main/java/com/metallum/mixin/render/BlockModelFeatureRendererMetalFxMixin.java", renderer_mixin)

unit = "src/test/java/com/metallum/client/metal/render/MetalFxMotionEligibilityTest.java"
replace_once(
    unit,
    "    @Test\n    void rejectionIsMonotonicWithinFrameAndResetsAtBoundary() {\n",
    "    @Test\n"
    "    void buildSampleListPreservesOrderAndSize() {\n"
    "        MetalEntityMotionCapture.beginFrame();\n"
    "        java.util.List<String> source = java.util.List.of(\"a\", \"b\", \"c\");\n"
    "        java.util.List<String> wrapped = MetalEntityMotionCapture.activateBuildSampleOnAccess(source);\n"
    "        assertEquals(source.size(), wrapped.size());\n"
    "        assertEquals(source, wrapped);\n"
    "    }\n\n"
    "    @Test\n    void rejectionIsMonotonicWithinFrameAndResetsAtBoundary() {\n",
)

mixins_path = Path("src/main/resources/metallum.mixins.json")
mixins = json.loads(mixins_path.read_text())
client = mixins["client"]
for name, anchor in [
    ("render.BlockModelFeatureSubmitMetalFxMixin", "render.ItemFeatureSubmitMetalFxMixin"),
    ("render.BlockModelFeatureRendererMetalFxMixin", "render.ItemFeatureRendererMetalFxMixin"),
]:
    if name not in client:
        client.insert(client.index(anchor) + 1, name)
mixins_path.write_text(json.dumps(mixins, indent=2) + "\n")
