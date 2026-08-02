package com.metallum.client.metal.render;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dimension-owned Iris ProgramSet and fallback admission contract. */
final class IrisMetalDimensionProgramSetTest {
    private static final NamespacedId OVERWORLD = new NamespacedId("minecraft", "overworld");
    private static final NamespacedId NETHER = new NamespacedId("minecraft", "the_nether");
    private static final NamespacedId END = new NamespacedId("minecraft", "the_end");

    @Test
    void dimensionOverridesRemainDistinctAndUseTheSameMetalAdmission() throws Exception {
        Iris.testing = true;
        ShaderPack pack = new ShaderPack(fixturePath(), environmentDefines(), false);

        List<ProgramSet> sets = List.of(
                pack.getProgramSet(OVERWORLD),
                pack.getProgramSet(NETHER),
                pack.getProgramSet(END)
        );
        assertEquals("vec4(1.0, 0.0, 0.0, 1.0)", red(sets.get(0)));
        assertEquals("vec4(0.0, 1.0, 0.0, 1.0)", red(sets.get(1)));
        assertEquals("vec4(0.0, 0.0, 1.0, 1.0)", red(sets.get(2)));
        assertNotEquals(source(sets.get(0)), source(sets.get(1)));
        assertNotEquals(source(sets.get(1)), source(sets.get(2)));

        for (ProgramSet set : sets) {
            ProgramFallbackResolver resolver = new ProgramFallbackResolver(set);
            assertTrue(resolver.resolveNullable(ProgramId.Terrain) != null);
            IrisMetalPackAdmission.requireSupported(set, ColorSpace.SRGB);
        }
    }

    private static String red(final ProgramSet set) {
        String source = source(set);
        int start = source.indexOf("vec4(");
        int end = source.indexOf(");", start);
        return source.substring(start, end + 1);
    }

    private static String source(final ProgramSet set) {
        ProgramSource source = set.get(ProgramId.Terrain).orElseThrow();
        return source.getFragmentSource().orElseThrow();
    }

    private static Path fixturePath() throws URISyntaxException {
        return Path.of(IrisMetalDimensionProgramSetTest.class
                .getResource("/iris-conformance-dimensions/shaders")
                .toURI());
    }

    private static ImmutableList<StringPair> environmentDefines() {
        return StandardMacros.createStandardEnvironmentDefines();
    }
}
