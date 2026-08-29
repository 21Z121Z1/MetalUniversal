from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    source = p.read_text()
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{path}: anchor count={count}: {old[:100]!r}")
    p.write_text(source.replace(old, new, 1))


# MetalRenderPass: explicit actions belong to one render pass and may create
# exactly one native encoder. Re-opening would make a memoryless attachment
# attempt a second load/store cycle, so fail closed.
render_pass = "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"
replace_once(
    render_pass,
    "    @Nullable\n    private Vector4fc[] clearColors;\n    private boolean clearDepthEnabled;\n",
    "    @Nullable\n    private Vector4fc[] clearColors;\n"
    "    @Nullable\n    private final int[] explicitColorLoadActions;\n"
    "    @Nullable\n    private final int[] explicitColorStoreActions;\n"
    "    private boolean explicitColorActionsConsumed;\n"
    "    private boolean clearDepthEnabled;\n",
)
replace_once(
    render_pass,
    "            @Nullable final Vector4fc[] clearColors,\n            final boolean clearDepthEnabled,\n",
    "            @Nullable final Vector4fc[] clearColors,\n"
    "            @Nullable final int[] explicitColorLoadActions,\n"
    "            @Nullable final int[] explicitColorStoreActions,\n"
    "            final boolean clearDepthEnabled,\n",
)
replace_once(
    render_pass,
    "        this.clearColors = clearColors == null ? null : clearColors.clone();\n"
    "        this.clearDepthEnabled = clearDepthEnabled;\n",
    "        this.clearColors = clearColors == null ? null : clearColors.clone();\n"
    "        this.explicitColorLoadActions = explicitColorLoadActions == null\n"
    "                ? null : explicitColorLoadActions.clone();\n"
    "        this.explicitColorStoreActions = explicitColorStoreActions == null\n"
    "                ? null : explicitColorStoreActions.clone();\n"
    "        this.clearDepthEnabled = clearDepthEnabled;\n",
)
replace_once(
    render_pass,
    "        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(\n"
    "                colorTextureViews,\n"
    "                depthTextureView,\n"
    "                extent.getWidth(0),\n"
    "                extent.getHeight(0),\n"
    "                clearColorEnabled,\n"
    "                clearColorValues,\n"
    "                clearDepthNow,\n"
    "                clearDepthValue,\n"
    "                label == null ? \"unlabeled render pass\" : label\n"
    "        );\n",
    "        if (this.explicitColorActionsConsumed) {\n"
    "            throw new IllegalStateException(\n"
    "                    \"Render pass with explicit attachment actions cannot reopen its native encoder\"\n"
    "            );\n"
    "        }\n"
    "        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(\n"
    "                colorTextureViews,\n"
    "                depthTextureView,\n"
    "                extent.getWidth(0),\n"
    "                extent.getHeight(0),\n"
    "                clearColorEnabled,\n"
    "                clearColorValues,\n"
    "                clearDepthNow,\n"
    "                clearDepthValue,\n"
    "                label == null ? \"unlabeled render pass\" : label,\n"
    "                this.explicitColorLoadActions,\n"
    "                this.explicitColorStoreActions\n"
    "        );\n"
    "        if (this.explicitColorLoadActions != null) {\n"
    "            this.explicitColorActionsConsumed = true;\n"
    "        }\n",
)

# MetalCommandEncoder: default overload is unchanged; the private overload can
# override individual V3 slots with -1=automatic, 0=dontCare, 1=load/store,
# 2=clear/unknown. V2 never receives explicit actions.
encoder = "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java"
replace_once(
    encoder,
    "    private final Long2ObjectOpenHashMap<java.util.ArrayDeque<MemorySegment>> dynamicBackingPool = new Long2ObjectOpenHashMap<>();\n",
    "    static boolean explicitColorActionsAvailable() {\n"
    "        return renderPassDescriptorV3Active();\n"
    "    }\n\n"
    "    private final Long2ObjectOpenHashMap<java.util.ArrayDeque<MemorySegment>> dynamicBackingPool = new Long2ObjectOpenHashMap<>();\n",
)
old_signature = """    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView[] colorTextureViews,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            final int[] clearColorEnabled,
            final float[] clearColorValues,
            final boolean clearDepthEnabled,
            final double clearDepthValue,
            final String label
    ) {"""
new_signature = """    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView[] colorTextureViews,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            final int[] clearColorEnabled,
            final float[] clearColorValues,
            final boolean clearDepthEnabled,
            final double clearDepthValue,
            final String label
    ) {
        return renderCommandEncoder(
                colorTextureViews, depthTextureView, viewportWidth, viewportHeight,
                clearColorEnabled, clearColorValues, clearDepthEnabled, clearDepthValue,
                label, null, null
        );
    }

    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView[] colorTextureViews,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            final int[] clearColorEnabled,
            final float[] clearColorValues,
            final boolean clearDepthEnabled,
            final double clearDepthValue,
            final String label,
            final int @Nullable [] explicitColorLoadActions,
            final int @Nullable [] explicitColorStoreActions
    ) {"""
replace_once(encoder, old_signature, new_signature)
replace_once(
    encoder,
    "                || clearColorEnabled.length != colorTextureViews.length\n"
    "                || clearColorValues.length != colorTextureViews.length * 4) {\n"
    "            throw new IllegalArgumentException(\"Invalid Metal MRT attachment arrays\");\n"
    "        }\n\n"
    "        RenderGraphTelemetry.onPassRequested(label);\n",
    "                || clearColorEnabled.length != colorTextureViews.length\n"
    "                || clearColorValues.length != colorTextureViews.length * 4\n"
    "                || (explicitColorLoadActions == null) != (explicitColorStoreActions == null)\n"
    "                || (explicitColorLoadActions != null\n"
    "                && (explicitColorLoadActions.length != colorTextureViews.length\n"
    "                || explicitColorStoreActions.length != colorTextureViews.length))) {\n"
    "            throw new IllegalArgumentException(\"Invalid Metal MRT attachment arrays\");\n"
    "        }\n"
    "        boolean explicitColorActions = explicitColorLoadActions != null;\n"
    "        if (explicitColorActions && !renderPassDescriptorV3Active()) {\n"
    "            throw new IllegalStateException(\"Explicit color attachment actions require render-pass ABI V3\");\n"
    "        }\n\n"
    "        RenderGraphTelemetry.onPassRequested(label);\n",
)
replace_once(
    encoder,
    "        if (sameAttachments && !clearDepthEnabled && !hasClearColor(clearColorEnabled)) {\n",
    "        if (sameAttachments && !explicitColorActions\n"
    "                && !clearDepthEnabled && !hasClearColor(clearColorEnabled)) {\n",
)
replace_once(
    encoder,
    """            for (int index = 0; index < slotCount; index++) {
                if (colorTextureViews[index] == null) {
                    colorLoadActions[index] = 0;
                    colorStoreActions[index] = 0;
                } else {
                    colorLoadActions[index] = clearColorEnabled[index] != 0 ? 2 : 1;
                    colorStoreActions[index] = deferredColorStoreActive() ? 2 : 1;
                }
            }""",
    """            for (int index = 0; index < slotCount; index++) {
                int requestedLoad = explicitColorActions ? explicitColorLoadActions[index] : -1;
                int requestedStore = explicitColorActions ? explicitColorStoreActions[index] : -1;
                if (requestedLoad < -1 || requestedLoad > 2 || requestedStore < -1 || requestedStore > 2) {
                    throw new IllegalArgumentException("Invalid explicit Metal attachment action at slot " + index);
                }
                if (colorTextureViews[index] == null) {
                    if (requestedLoad > 0 || requestedStore > 0) {
                        throw new IllegalArgumentException("Unused color slot cannot request load/store");
                    }
                    colorLoadActions[index] = 0;
                    colorStoreActions[index] = 0;
                } else {
                    colorLoadActions[index] = requestedLoad >= 0
                            ? requestedLoad
                            : clearColorEnabled[index] != 0 ? 2 : 1;
                    colorStoreActions[index] = requestedStore >= 0
                            ? requestedStore
                            : deferredColorStoreActive() ? 2 : 1;
                }
            }""",
)
replace_once(
    encoder,
    """    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {""",
    """    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        return createRenderPass(descriptor, null, null);
    }

    MetalRenderPass createRenderPass(
            final RenderPassDescriptor descriptor,
            final int @Nullable [] explicitColorLoadActions,
            final int @Nullable [] explicitColorStoreActions
    ) {""",
)
replace_once(
    encoder,
    "        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();\n",
    "        if ((explicitColorLoadActions == null) != (explicitColorStoreActions == null)\n"
    "                || (explicitColorLoadActions != null\n"
    "                && (explicitColorLoadActions.length != colorAttachments.size()\n"
    "                || explicitColorStoreActions.length != colorAttachments.size()))) {\n"
    "            throw new IllegalArgumentException(\"Explicit attachment actions must match color slots\");\n"
    "        }\n"
    "        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();\n",
)
replace_once(
    encoder,
    "                renderArea,\n"
    "                hasColorClear ? clearColors : null,\n"
    "                depthClear.isPresent(),\n",
    "                renderArea,\n"
    "                hasColorClear ? clearColors : null,\n"
    "                explicitColorLoadActions,\n"
    "                explicitColorStoreActions,\n"
    "                depthClear.isPresent(),\n",
)

Path("src/test/java/com/metallum/client/metal/render/MetalExplicitAttachmentActionContractTest.java").write_text(
    """package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalExplicitAttachmentActionContractTest {
    @Test
    void privateActionsRequireV3AndDefaultPathRemainsAutomatic() throws Exception {
        String encoder = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java"));
        String pass = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"));
        assertTrue(encoder.contains("label, null, null"));
        assertTrue(encoder.contains("Explicit color attachment actions require render-pass ABI V3"));
        assertTrue(encoder.contains("requestedLoad >= 0"));
        assertTrue(encoder.contains("requestedStore >= 0"));
        assertTrue(encoder.contains("colorStoreActions[index] = requestedStore >= 0"));
        assertTrue(pass.contains("explicitColorActionsConsumed"));
        assertTrue(pass.contains("cannot reopen its native encoder"));
    }
}
"""
)
