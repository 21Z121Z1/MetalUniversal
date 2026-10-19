package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class MetalFxRasterConventionTest {
    @Test
    void actualShippingTranslatorFlipsRasterY() throws Exception {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long result = Shaderc.shaderc_compile_into_spv(compiler, """
                #version 450
                layout(location=0) in vec3 Position;
                void main() { gl_Position = vec4(Position, 1); }
                """, Shaderc.shaderc_vertex_shader, "motion-coordinate-policy", "main", 0);
        ByteBuffer spirv = null;
        try {
            assertEquals(Shaderc.shaderc_compilation_status_success, Shaderc.shaderc_result_get_compilation_status(result));
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            spirv = MemoryUtil.memAlloc(bytes.remaining()).put(bytes).flip();
            String msl = MetalCrossShaderCompiler.spirvToMsl(spirv, 0, Map.of(), Map.of()).source();
            Path path = Path.of("build/agent-evidence/metalfx-closure/shipping-raster-policy.metal");
            Files.createDirectories(path.getParent());Files.writeString(path, msl);
            assertTrue(msl.matches("(?s).*\\.gl_Position\\.y\\s*=\\s*-.*"), msl);
        } finally {
            if (spirv != null) MemoryUtil.memFree(spirv);
            Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    @Test
    void objectMotionAgreesWithActualCompilerRasterOrientation() {
        var originalPrevious = new Matrix4f().translate(0, .25F, 0);
        var motion = MetalMotionContract.projectVertex(new Vector4f(0, 0, .5F, 1), new Matrix4f(),
                MetalMotionContract.minecraftToMetalFxClip(new Matrix4f()), new Matrix4f(),
                MetalMotionContract.minecraftToMetalFxClip(new Matrix4f()), originalPrevious);
        float expectedPixels = rasterY(new Vector4f(0, .25F, .5F, 1), 48)
                - rasterY(new Vector4f(0, 0, .5F, 1), 48);
        assertEquals(expectedPixels, motion.motionNdc().y * 24, 1E-5F);
    }

    @Test
    void cameraMotionUsesTheSamePostCompilerCoordinateSpace() {
        var current = new Matrix4f();
        var previous = new Matrix4f().translate(0, .25F, 0);
        var inverse = new Matrix4f();
        var actual = MetalFxMath.reconstructMotion(.5F, 32, 24, 64, 48,
                new Matrix4f().set(MetalMotionContract.cameraMatrix(current, new float[16], false)),
                new Matrix4f().set(MetalMotionContract.cameraMatrix(inverse, new float[16], true)),
                new Matrix4f().set(MetalMotionContract.cameraMatrix(previous, new float[16], false)));
        assertEquals(6, actual.y, 1E-5F, "Previous +Y maps to larger raw-texture rows after compiler Y flip");
    }

    @Test
    void reportedJitterMatchesActualRasterPixelsAfterCompilerFlip() {
        int width = 1000, height = 500;
        var projection = new Matrix4f().perspective((float)Math.toRadians(70), 2, .05F, 1000);
        var point = new Vector4f(1, 1, -10, 1);
        var before = new Vector4f(point).mul(projection);
        var jitter = new Vector2f(.25F, -.5F);
        var clip = MetalFxMath.clipJitter(jitter, width, height);
        MetalMotionContract.minecraftRasterJitter(clip);
        MetalFxMath.applyProjectionJitter(projection, clip);
        var after = new Vector4f(point).mul(projection);
        assertEquals(jitter.y, rasterY(after, height) - rasterY(before, height), 1E-3F);
        assertEquals(jitter.x, (after.x/after.w - before.x/before.w)*width/2, 1E-3F);
    }

    private static float rasterY(Vector4f sourceClip, int height) {
        // Independently apply the Y flip verified in the actual translated MSL above.
        return (1 - (-sourceClip.y/sourceClip.w)) * height/2;
    }
}
