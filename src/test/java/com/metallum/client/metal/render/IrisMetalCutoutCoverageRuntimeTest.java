package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalCutoutCoverageRuntimeTest {
    @Test
    void coverageUsesCompactSlotAndPrecedesDiscard() {
        String source = """
                #version 330 core
                layout(location = 0) out vec4 color0;
                layout(location = 1) out vec4 color1;
                void main() {
                    if (color0.a < 0.5) discard;
                    color1 = color0;
                }
                """;

        String patched = IrisMetalCutoutCoverageRuntime.injectCoverageOutput(source, 2);

        String declaration = "layout(location = 2) out float metallum_MetalFxCutoutCoverage;";
        String assignment = "metallum_MetalFxCutoutCoverage = 1.0;";
        assertTrue(patched.contains(declaration));
        assertTrue(patched.indexOf(declaration) < patched.indexOf("void main()"));
        assertTrue(patched.indexOf(assignment) < patched.indexOf("discard"));
        assertEquals(1, occurrences(patched, assignment));
    }

    @Test
    void acceptsExplicitVoidMainSignature() {
        String source = "#version 330 core\nvoid main(void) { return; }\n";
        String patched = IrisMetalCutoutCoverageRuntime.injectCoverageOutput(source, 0);
        assertTrue(patched.contains("layout(location = 0) out float metallum_MetalFxCutoutCoverage;"));
        assertTrue(patched.contains("metallum_MetalFxCutoutCoverage = 1.0;"));
    }

    @Test
    void rejectsMissingMainInsteadOfSilentlyApproximatingCoverage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalCutoutCoverageRuntime.injectCoverageOutput("#version 330 core\n", 1)
        );
    }

    @Test
    void rejectsReservedOutputCollision() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalCutoutCoverageRuntime.injectCoverageOutput(
                        "float metallum_MetalFxCutoutCoverage; void main() {}",
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
