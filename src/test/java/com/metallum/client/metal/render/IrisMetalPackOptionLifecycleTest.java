package com.metallum.client.metal.render;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.option.OptionSet;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixed-Iris option/profile admission contract without a pack-name branch. */
final class IrisMetalPackOptionLifecycleTest {
    private static final NamespacedId OVERWORLD = new NamespacedId("minecraft", "overworld");

    @Test
    void booleanProfileAndSliderOptionChangeTheProgramSetBeforeMetalAdmission() throws Exception {
        Iris.testing = true;
        ShaderPack minimal = load(Map.of("OPTION_COLOR", "false", "OPTION_LEVEL", "1"));
        ShaderPack full = load(Map.of("OPTION_COLOR", "true", "OPTION_LEVEL", "2"));
        ShaderPack sliderOverride = load(Map.of("OPTION_COLOR", "false", "OPTION_LEVEL", "2"));

        OptionSet options = full.getShaderPackOptions().getOptionSet();
        assertTrue(options.getBooleanOptions().containsKey("OPTION_COLOR"));
        assertTrue(options.getStringOptions().containsKey("OPTION_LEVEL"));
        assertTrue(full.getProfileInfo().contains("Profile: FULL"));
        assertTrue(minimal.getProfileInfo().contains("Profile: MINIMAL"));
        assertTrue(sliderOverride.getProfileInfo().contains("options changed by user"));

        IrisMetalPackAdmission.requireSupported(minimal.getProgramSet(OVERWORLD), ColorSpace.SRGB);
        IrisMetalPackAdmission.requireSupported(full.getProgramSet(OVERWORLD), ColorSpace.SRGB);
        IrisMetalPackAdmission.requireSupported(sliderOverride.getProgramSet(OVERWORLD), ColorSpace.SRGB);

        String minimalSource = fragment(minimal.getProgramSet(OVERWORLD));
        String fullSource = fragment(full.getProgramSet(OVERWORLD));
        assertNotEquals(minimalSource, fullSource);
        assertTrue(minimalSource.contains("vec4(0.0, 0.0, 1.0, 1.0)"), minimalSource);
        assertTrue(fullSource.contains("vec4(1.0, 0.0, 0.0, 1.0)"), fullSource);
        assertFalse(minimalSource.contains("gl_FragColor.rgb ="), minimalSource);
        assertTrue(fullSource.contains("gl_FragColor.rgb ="), fullSource);
        assertTrue(fragment(sliderOverride.getProgramSet(OVERWORLD))
                .contains("vec4(1.0, 0.0, 0.0, 1.0)"));
    }

    @Test
    void queuedOptionsAreConsumedAsOneIrisPackSelection() throws Exception {
        Iris.testing = true;
        Map<String, String> queue = Iris.getShaderPackOptionQueue();
        queue.clear();
        queue.put("OPTION_COLOR", "false");
        queue.put("OPTION_LEVEL", "1");
        ShaderPack queued = load(queue);
        queue.clear();

        assertTrue(queued.getProfileInfo().contains("Profile: MINIMAL"));
        assertTrue(
                fragment(queued.getProgramSet(OVERWORLD)).contains("vec4(0.0, 0.0, 1.0, 1.0)"),
                fragment(queued.getProgramSet(OVERWORLD))
        );
    }

    private static ShaderPack load(final Map<String, String> options) throws Exception {
        return new ShaderPack(fixturePath(), options, environmentDefines(), false);
    }

    private static String fragment(final ProgramSet set) {
        ProgramSource source = set.get(ProgramId.Terrain).orElseThrow();
        return source.getFragmentSource().orElseThrow();
    }

    private static Path fixturePath() throws URISyntaxException {
        return Path.of(IrisMetalPackOptionLifecycleTest.class
                .getResource("/iris-conformance-options/shaders")
                .toURI());
    }

    private static ImmutableList<StringPair> environmentDefines() {
        return StandardMacros.createStandardEnvironmentDefines();
    }
}
