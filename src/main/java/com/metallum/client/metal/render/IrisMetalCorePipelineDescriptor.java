package com.metallum.client.metal.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.joml.Vector4fc;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Builds the render-pass attachment contract for one fixed-Iris core draw. */
final class IrisMetalCorePipelineDescriptor {
    private IrisMetalCorePipelineDescriptor() {
    }

    static RenderPassDescriptor main(
            final IrisMetalWorldResources resources,
            final Supplier<String> label,
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        int[] drawBuffers = drawBuffers(program);
        return resources.renderTargets().createTerrainWriteDescriptor(
                label.get(),
                drawBuffers,
                sceneColor,
                clearColor.orElse(null),
                sceneDepth,
                clearDepth.isPresent() ? clearDepth.getAsDouble() : null
        );
    }

    static RenderPassDescriptor shadow(
            final IrisMetalWorldResources resources,
            final Supplier<String> label,
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final Optional<Vector4fc> clearColor,
            final OptionalDouble clearDepth
    ) {
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if (shadows == null) {
            throw new IllegalStateException("Iris shadow core draw has no generation-owned shadow targets");
        }
        int[] drawBuffers = drawBuffers(program);
        Vector4fc[] clearColors = null;
        if (clearColor.isPresent()) {
            clearColors = new Vector4fc[drawBuffers.length];
            clearColors[0] = clearColor.orElseThrow();
        }
        return shadows.createShadowGbufferDescriptor(
                label.get(),
                drawBuffers,
                clearColors,
                clearDepth.isPresent() ? clearDepth.getAsDouble() : null
        ).descriptor();
    }

    static int[] drawBuffers(final IrisMetalGlslLinker.LinkedRasterProgram program) {
        int[] declared = program.program().drawBuffers();
        return declared.length == 0 ? new int[]{0} : declared.clone();
    }
}
