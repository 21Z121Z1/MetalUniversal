package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalNativeBridgeCriticalPolicyTest {
    private static final Path BRIDGE = Path.of(
            "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
    );

    @Test
    void synchronousCompilerBoundariesAreNeverCriticalDowncalls() throws IOException {
        String source = Files.readString(BRIDGE);

        assertTrue(source.contains(
                "initPipelines = downcallWithoutCritical(lookup, \"metallum_init_pipelines\""
        ));
        assertTrue(source.contains(
                "MTLDeviceMakeRenderPipelineState = downcallWithoutCritical("
        ));
        assertTrue(source.contains(
                "MTLDeviceMakeComputePipelineState = optionalDowncallWithoutCritical("
        ));
        assertTrue(source.contains(
                "private static MethodHandle optionalDowncallWithoutCritical("
        ));

        assertFalse(source.contains(
                "MTLDeviceMakeRenderPipelineState = downcall("
        ));
        assertFalse(source.contains(
                "MTLDeviceMakeComputePipelineState = optionalDowncall("
        ));
    }

    @Test
    void metalPipelineConstructionNeverUsesCriticalDowncalls() throws IOException {
        String source = Files.readString(BRIDGE);

        assertTrue(source.contains(
                "createSystemDefaultDevice = downcallWithoutCritical(lookup, \"metallum_create_system_default_device\""
        ));
        assertTrue(source.contains("MTLVertexDescriptorCreate = downcallWithoutCritical("));
        assertTrue(source.contains("MTLVertexDescriptorSetAttribute = downcallWithoutCritical("));
        assertTrue(source.contains("MTLVertexDescriptorSetLayout = downcallWithoutCritical("));
        assertTrue(source.contains("MTLRenderPipelineDescriptorCreate = downcallWithoutCritical("));
        assertTrue(source.contains("MTLRenderPipelineDescriptorSetCompiledFunctions = downcallWithoutCritical("));
        assertTrue(source.contains("MTLRenderPipelineDescriptorSetVertexDescriptor = downcallWithoutCritical("));
        assertTrue(source.contains("MTLRenderPipelineDescriptorSetAttachmentFormats = downcallWithoutCritical("));
        assertTrue(source.contains("MTLRenderPipelineDescriptorSetColorAttachmentFormat = optionalDowncallWithoutCritical("));
        assertTrue(source.contains("MTLRenderPipelineDescriptorSetDepthStencilFormats = optionalDowncallWithoutCritical("));
        assertTrue(source.contains("MTLRenderPipelineDescriptorSetColorAttachmentBlendState = optionalDowncallWithoutCritical("));
        assertTrue(source.contains("MTLRenderPipelineDescriptorSetBlendState = downcallWithoutCritical("));

        assertFalse(source.contains("MTLVertexDescriptorCreate = downcall("));
        assertFalse(source.contains("MTLRenderPipelineDescriptorCreate = downcall("));
        assertFalse(source.contains("MTLRenderPipelineDescriptorSetCompiledFunctions = downcall("));
        assertFalse(source.contains("MTLRenderPipelineDescriptorSetColorAttachmentFormat = optionalDowncall("));
    }

    @Test
    void shortEncoderStateCallsKeepTheCriticalFastPath() throws IOException {
        String source = Files.readString(BRIDGE);

        assertTrue(source.contains(
                "MTLRenderCommandEncoderSetRenderPipelineState = downcall("
        ));
        assertTrue(source.contains(
                "MTLComputeCommandEncoderSetComputePipelineState = optionalDowncall("
        ));
    }
}
