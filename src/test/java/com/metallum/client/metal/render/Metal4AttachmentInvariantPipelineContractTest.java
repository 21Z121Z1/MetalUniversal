package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class Metal4AttachmentInvariantPipelineContractTest {
    @Test
    void metal4DoesNotManufactureDepthStencilPsoVariants() throws Exception {
        String javaSource = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalCompiledRenderPipeline.java"
        ));
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(javaSource.contains("if (this.device.metal4MainRendererEnabled())"));
        assertTrue(javaSource.contains(
                "return new PipelineSignature(\n                    this.colorFormatsView,\n                    MTLPixelFormat.Invalid,"
        ));
        assertTrue(javaSource.contains("if (states.containsKey(signature))"));
        assertTrue(javaSource.contains("this.lazyVariants && !device.metal4MainRendererEnabled()"));

        // Native translation intentionally omits depth/stencil formats in MTL4.
        int descriptor = nativeSource.indexOf("private func makeMetal4Descriptor(");
        int archive = nativeSource.indexOf("/// Opens (or creates) the on-disk PSO", descriptor);
        assertTrue(descriptor >= 0 && archive > descriptor);
        String metal4Descriptor = nativeSource.substring(descriptor, archive);
        assertTrue(metal4Descriptor.contains("MTL4RenderPipelineDescriptor()"));
        assertTrue(metal4Descriptor.contains("colorAttachments"));
        assertTrue(!metal4Descriptor.contains("depthAttachmentPixelFormat"));
        assertTrue(!metal4Descriptor.contains("stencilAttachmentPixelFormat"));
    }
}
