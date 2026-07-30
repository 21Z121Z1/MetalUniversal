package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalProgramFrontendTest {
    private static final String FIXTURE = "/shaderpacks/BSL_v10.1.3.zip";

    @Test
    void realBslProgramSetUsesIrisFallbackAndTransforms() throws IOException {
        Iris.testing = true;
        try (LoadedPack loaded = loadFixture()) {
            ProgramSet programs = loaded.pack().getProgramSet(new NamespacedId("minecraft", "overworld"));
            IrisMetalProgramFrontend frontend = new IrisMetalProgramFrontend(programs);

            IrisMetalProgramFrontend.ResolvedProgram terrain = frontend.resolve(ProgramId.TerrainSolid)
                    .orElseThrow();
            assertTrue(terrain.usedFallback(), "BSL terrain_solid should resolve through Iris to terrain");
            assertTrue(terrain.source().getName().endsWith("gbuffers_terrain"));

            IrisMetalProgramFrontend.RasterProgram patchedTerrain =
                    frontend.patchSodium(terrain, AlphaTest.ALWAYS);
            assertRequiredRasterStages(patchedTerrain);
            assertArrayEquals(terrain.source().getDirectives().getDrawBuffers(), patchedTerrain.drawBuffers());
            assertFalse(patchedTerrain.requiresUnsupportedMetalStage());
            assertPatchedCoreSyntax(patchedTerrain.vertexSource());
            assertPatchedCoreSyntax(patchedTerrain.fragmentSource());

            ProgramSource composite = Arrays.stream(programs.getComposite(ProgramArrayId.Composite))
                    .filter(source -> source != null && source.isValid())
                    .findFirst()
                    .orElseThrow();
            IrisMetalProgramFrontend.RasterProgram patchedComposite =
                    frontend.patchComposite(composite, TextureStage.COMPOSITE_AND_FINAL);
            assertRequiredRasterStages(patchedComposite);
            assertArrayEquals(composite.getDirectives().getDrawBuffers(), patchedComposite.drawBuffers());
            assertPatchedCoreSyntax(patchedComposite.vertexSource());
            assertPatchedCoreSyntax(patchedComposite.fragmentSource());

            ProgramSource finalSource = programs.get(ProgramId.Final).orElseThrow();
            IrisMetalProgramFrontend.RasterProgram patchedFinal =
                    frontend.patchComposite(finalSource, TextureStage.COMPOSITE_AND_FINAL);
            assertRequiredRasterStages(patchedFinal);
            assertPatchedCoreSyntax(patchedFinal.vertexSource());
            assertPatchedCoreSyntax(patchedFinal.fragmentSource());
        }
    }

    private static void assertRequiredRasterStages(final IrisMetalProgramFrontend.RasterProgram program) {
        assertNotNull(program.stages().get(PatchShaderType.VERTEX));
        assertNotNull(program.stages().get(PatchShaderType.FRAGMENT));
    }

    private static void assertPatchedCoreSyntax(final String source) {
        assertFalse(source.matches("(?s).*\\b(attribute|varying|ftransform)\\b.*"), source);
    }

    private static LoadedPack loadFixture() throws IOException {
        Path zip = Files.createTempFile("bsl-frontend-", ".zip");
        zip.toFile().deleteOnExit();
        try (var input = IrisMetalProgramFrontendTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(input, "missing BSL fixture " + FIXTURE);
            Files.copy(input, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        FileSystem fileSystem = FileSystems.newFileSystem(zip, Map.of());
        try {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            return new LoadedPack(fileSystem, pack);
        } catch (Throwable throwable) {
            fileSystem.close();
            throw throwable;
        }
    }

    private record LoadedPack(FileSystem fileSystem, ShaderPack pack) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            fileSystem.close();
        }
    }
}
