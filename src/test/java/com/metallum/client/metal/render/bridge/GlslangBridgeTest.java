package com.metallum.client.metal.render.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlslangBridgeTest {
    @Test
    void normalizesDesktopVertexBuiltinsForVulkanClientSemantics() {
        String source = "void main() { int a = gl_VertexID; int b = gl_InstanceID; }";

        assertEquals(
                "void main() { int a = gl_VertexIndex; int b = gl_InstanceIndex; }",
                GlslangBridge.normalizeVulkanBuiltins(GlslangBridge.Stage.VERTEX, source)
        );
        assertEquals(
                source,
                GlslangBridge.normalizeVulkanBuiltins(GlslangBridge.Stage.FRAGMENT, source)
        );
    }
}
