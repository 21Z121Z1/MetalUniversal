from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    source = p.read_text()
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{path}: anchor count={count}: {old[:120]!r}")
    p.write_text(source.replace(old, new, 1))


# Allow a Metallum-private pass to substitute selected color views while keeping
# every existing call site and persistent-view ownership rule unchanged.
targets = "src/main/java/com/metallum/client/metal/render/IrisMetalRenderTargets.java"
old_signature = """    RenderPassDescriptorWithViews createWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            @Nullable final Vector4fc[] clearColors,
            final boolean withDepth,
            @Nullable final Double clearDepth,
            final int @Nullable [] readTargets
    ) {
        ensureOpen();"""
new_signature = """    RenderPassDescriptorWithViews createWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            @Nullable final Vector4fc[] clearColors,
            final boolean withDepth,
            @Nullable final Double clearDepth,
            final int @Nullable [] readTargets
    ) {
        return createWriteDescriptor(
                label, drawBuffers, clearColors, withDepth, clearDepth, readTargets, null
        );
    }

    RenderPassDescriptorWithViews createWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            @Nullable final Vector4fc[] clearColors,
            final boolean withDepth,
            @Nullable final Double clearDepth,
            final int @Nullable [] readTargets,
            final MetalGpuTextureView @Nullable [] colorOverrides
    ) {
        ensureOpen();"""
replace_once(targets, old_signature, new_signature)
replace_once(
    targets,
    "        if (clearColors != null && clearColors.length != drawBuffers.length) {\n"
    "            throw new IllegalArgumentException(\"Clear color array must match draw buffer count\");\n"
    "        }\n",
    "        if (clearColors != null && clearColors.length != drawBuffers.length) {\n"
    "            throw new IllegalArgumentException(\"Clear color array must match draw buffer count\");\n"
    "        }\n"
    "        if (colorOverrides != null && colorOverrides.length != drawBuffers.length) {\n"
    "            throw new IllegalArgumentException(\"Color override array must match draw buffer count\");\n"
    "        }\n",
)
replace_once(
    targets,
    "        for (int slot = 0; slot < drawBuffers.length; slot++) {\n"
    "            MetalGpuTextureView view = colorTargets.writeView(drawBuffers[slot]);\n",
    "        for (int slot = 0; slot < drawBuffers.length; slot++) {\n"
    "            MetalGpuTextureView view = colorOverrides != null && colorOverrides[slot] != null\n"
    "                    ? colorOverrides[slot]\n"
    "                    : colorTargets.writeView(drawBuffers[slot]);\n"
    "            if (view.getWidth(0) != width || view.getHeight(0) != height\n"
    "                    || view.texture().getFormat() != colorTargets.format(drawBuffers[slot])) {\n"
    "                throw new IllegalArgumentException(\n"
    "                        \"Color override does not match Iris target \" + drawBuffers[slot]\n"
    "                );\n"
    "            }\n",
)

# Carry the per-stage raster ordinal used by the immutable plan receipt into the
# execution site. Compute groups do not perturb this ordinal.
post = "src/main/java/com/metallum/client/metal/render/IrisMetalPostChain.java"
replace_once(
    post,
    """                if (rasterIndex == index) {
                    PlannedPass pass = raster.get(rasterCursor++);
                    executePass(device, targets, resources, pass);
                    colors.restore(pass.info.stateAfter());
                    executed.add(pass.info.name());
                }""",
    """                if (rasterIndex == index) {
                    int renderOrdinal = rasterCursor;
                    PlannedPass pass = raster.get(rasterCursor++);
                    executePass(device, targets, resources, pass, renderOrdinal);
                    colors.restore(pass.info.stateAfter());
                    executed.add(pass.info.name());
                }""",
)
replace_once(
    post,
    """    private void executePass(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources,
            final PlannedPass pass
    ) {""",
    """    private void executePass(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final ResourceProvider resources,
            final PlannedPass pass,
            final int renderOrdinal
    ) {""",
)
old_body = """        RenderPass.RenderArea area = renderArea(pass.viewport, targets.width(), targets.height());
        try (IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor = targets.createWriteDescriptor(
                "Iris " + pass.info.stage().name().toLowerCase(Locale.ROOT) + ": " + pass.info.name(),
                pass.info.drawBuffers(),
                null,
                false,
                null,
                null
        )) {
            descriptor.descriptor().withRenderArea(area);
            MetalCommandEncoder encoder = device.commandEncoder();
            MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
            try {
                renderFullscreen(
                        renderPass,
                        Objects.requireNonNull(pass.pipeline, "post pipeline"),
                        pass.info,
                        pass.program,
                        pass.blendState.global(),
                        targets,
                        resources
                );
            } finally {
                encoder.submitRenderPass();
            }
        }"""
new_body = """        RenderPass.RenderArea area = renderArea(pass.viewport, targets.width(), targets.height());
        try (IrisMetalMemorylessPassAttachments memoryless =
                     IrisMetalMemorylessPassAttachments.tryCreate(
                             device, this.generation, targets, pass.info, renderOrdinal
                     );
             IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor = targets.createWriteDescriptor(
                     "Iris " + pass.info.stage().name().toLowerCase(Locale.ROOT) + ": " + pass.info.name(),
                     pass.info.drawBuffers(),
                     null,
                     false,
                     null,
                     null,
                     memoryless == null ? null : memoryless.views()
             )) {
            descriptor.descriptor().withRenderArea(area);
            MetalCommandEncoder encoder = device.commandEncoder();
            MetalRenderPass renderPass = memoryless == null
                    ? (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor())
                    : encoder.createRenderPass(
                            descriptor.descriptor(),
                            memoryless.loadActions(),
                            memoryless.storeActions()
                    );
            try {
                renderFullscreen(
                        renderPass,
                        Objects.requireNonNull(pass.pipeline, "post pipeline"),
                        pass.info,
                        pass.program,
                        pass.blendState.global(),
                        targets,
                        resources
                );
            } finally {
                encoder.submitRenderPass();
            }
        }"""
replace_once(post, old_body, new_body)
