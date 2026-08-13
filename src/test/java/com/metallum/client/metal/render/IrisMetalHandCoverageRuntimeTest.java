package com.metallum.client.metal.render;

import net.irisshaders.iris.pipeline.programs.ShaderKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalHandCoverageRuntimeTest {
    @Test
    void allFirstPersonShaderKeysOwnExactCoverage() {
        assertTrue(IrisMetalHandCoverageRuntime.isHandKey(ShaderKey.HAND_CUTOUT));
        assertTrue(IrisMetalHandCoverageRuntime.isHandKey(ShaderKey.HAND_CUTOUT_DIFFUSE));
        assertTrue(IrisMetalHandCoverageRuntime.isHandKey(ShaderKey.HAND_WATER_DIFFUSE));
        assertTrue(IrisMetalHandCoverageRuntime.isHandKey(ShaderKey.HAND_TRANSLUCENT));
        assertTrue(IrisMetalHandCoverageRuntime.isHandKey(ShaderKey.HAND_TEXT));
        assertTrue(IrisMetalHandCoverageRuntime.isHandKey(ShaderKey.HAND_TEXT_TRANSLUCENT));
        assertFalse(IrisMetalHandCoverageRuntime.isHandKey(ShaderKey.ENTITIES_TRANSLUCENT));
        assertFalse(IrisMetalHandCoverageRuntime.isHandKey(ShaderKey.TERRAIN_CUTOUT));
    }

    @Test
    void handCoverageIsForwardDepthSurrogateAndDiscardSafe() {
        String source = """
                #version 330 core
                layout(location = 0) out vec4 color0;
                layout(location = 1) out vec4 color1;
                void main() {
                    if (color0.a < 0.01) discard;
                    color1 = color0;
                }
                """;

        String patched = IrisMetalHandCoverageRuntime.injectCoverageOutput(source, 2);

        String declaration = "layout(location = 2) out float metallum_MetalFxHandCoverage;";
        String assignment = "metallum_MetalFxHandCoverage = 0.0;";
        assertTrue(patched.contains(declaration));
        assertTrue(patched.indexOf(declaration) < patched.indexOf("void main()"));
        assertTrue(patched.indexOf(assignment) < patched.indexOf("discard"));
        assertEquals(1, occurrences(patched, assignment));
        // The resource is cleared to 1.0. Existing forward-Z native hand logic treats
        // 0.0 as valid/near and 1.0 as invalid/far, so no native ABI mode bit is needed.
        assertFalse(MetalIrisDepthConvention.depthReversed(false));
    }

    @Test
    void acceptsExplicitVoidMainSignature() {
        String patched = IrisMetalHandCoverageRuntime.injectCoverageOutput(
                "#version 330 core\nvoid main(void) { return; }\n",
                0
        );
        assertTrue(patched.contains("layout(location = 0) out float metallum_MetalFxHandCoverage;"));
        assertTrue(patched.contains("metallum_MetalFxHandCoverage = 0.0;"));
    }

    @Test
    void rejectsUnsupportedShaderShapeInsteadOfApproximating() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalHandCoverageRuntime.injectCoverageOutput("#version 330 core\n", 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalHandCoverageRuntime.injectCoverageOutput(
                        "float metallum_MetalFxHandCoverage; void main() {}",
                        1
                )
        );
    }

    private static int occurrences(final String text, final String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
