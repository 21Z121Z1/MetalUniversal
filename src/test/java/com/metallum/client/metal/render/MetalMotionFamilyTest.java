package com.metallum.client.metal.render;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the motion-family mapping and the shader assets it names.
 *
 * <p>A family whose shader identifier does not resolve to a file fails only when
 * Minecraft first tries to compile that variant, which is deep inside a frame and
 * after the geometry has already been split out of its batch. Checking the assets
 * exist here turns that into a build failure.</p>
 */
final class MetalMotionFamilyTest {
    private static final Path SHADER_ROOT = Path.of("src/main/resources/assets/metallum/shaders");

    @Test
    void everyFamilyNamesShaderAssetsThatExist() {
        for (MetalEntityMotionPipeline.Family family : MetalEntityMotionPipeline.Family.values()) {
            String path = family.shader().getPath();
            assertEquals("metallum", family.shader().getNamespace(),
                    family + " must resolve inside this mod's asset namespace");
            for (String stage : new String[] { ".vsh", ".fsh" }) {
                Path asset = SHADER_ROOT.resolve(path + stage);
                assertTrue(Files.isRegularFile(asset),
                        family + " names " + asset + ", which does not exist; the variant would fail at"
                                + " shader compilation mid-frame");
            }
        }
    }

    @Test
    void familiesDoNotShareALocationPrefix() {
        MetalEntityMotionPipeline.Family[] families = MetalEntityMotionPipeline.Family.values();
        for (int first = 0; first < families.length; first++) {
            for (int second = first + 1; second < families.length; second++) {
                assertTrue(!families[first].locationPrefix().equals(families[second].locationPrefix()),
                        families[first] + " and " + families[second] + " share a pipeline location prefix,"
                                + " so two variants of the same source pipeline would collide");
            }
        }
    }

    @Test
    void theBlockShaderAppliesModelOffsetAndTheEntityShaderDoesNot() {
        String blockVertex = read(MetalEntityMotionPipeline.Family.BLOCK.shader().getPath() + ".vsh");
        String entityVertex = read(MetalEntityMotionPipeline.Family.ENTITY.shader().getPath() + ".vsh");

        // core/block computes gl_Position from Position + ModelOffset; core/entity
        // from Position alone. Replaying either with the other family's transform
        // yields motion vectors that are plausible and wrong, so this is the one
        // difference that must never be lost.
        assertTrue(blockVertex.contains("Position + ModelOffset"),
                "the block family must reproduce core/block's ModelOffset term");
        assertTrue(!entityVertex.contains("ModelOffset"),
                "the entity family must not add an offset core/entity never applies");
    }

    @Test
    void bothFamiliesReconstructRasterClipBeforeRemovingJitter() {
        for (MetalEntityMotionPipeline.Family family : MetalEntityMotionPipeline.Family.values()) {
            String vertex = read(family.shader().getPath() + ".vsh");
            assertTrue(vertex.contains("CurrentUnjitteredFromRaster") && vertex.contains("PreviousFromRaster"),
                    family + " must take both clip transforms from the MetallumMotion block");
            assertTrue(vertex.contains("gl_Position = rasterClip"),
                    family + " must rasterise at the jittered position the color pass used, or the motion"
                            + " target will not line up with the scene");
        }
    }

    @Test
    void bothFamiliesDeclareExplicitAttributeLocations() {
        for (MetalEntityMotionPipeline.Family family : MetalEntityMotionPipeline.Family.values()) {
            String vertex = read(family.shader().getPath() + ".vsh");
            // Without explicit locations SPIR-V packs the declared attributes
            // densely and UV0 lands on attribute 1, so the alpha-test replay
            // samples vertex colors and discards everything.
            assertTrue(vertex.contains("layout(location = 0) in vec3 Position"), family + " Position location");
            assertTrue(vertex.contains("layout(location = 1) in vec4 Color"), family + " Color location");
            assertTrue(vertex.contains("layout(location = 2) in vec2 UV0"), family + " UV0 location");
        }
    }

    private static String read(final String relative) {
        Path asset = SHADER_ROOT.resolve(relative);
        try {
            return Files.readString(asset);
        } catch (Exception failure) {
            throw new AssertionError("cannot read " + asset.toAbsolutePath(), failure);
        }
    }
}
