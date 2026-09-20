package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

final class MetalRenderPearlBoundaryTest {
    @Test
    void mutableSpirvCopyNeverAliasesRenderPearlOwnedStorage() {
        ByteBuffer original = memAlloc(24).order(ByteOrder.nativeOrder());
        ByteBuffer copy = null;
        try {
            original.putInt(0x07230203);
            original.putInt(0x00010600);
            original.putInt(7);
            original.putInt(8);
            original.putInt(9);
            original.putInt(10);
            original.flip();

            int originalPosition = original.position();
            int originalLimit = original.limit();
            int originalWord = original.getInt(8);

            copy = MetalCrossShaderCompiler.mutableSpirvCopy(original);
            copy.putInt(8, originalWord ^ 0x55aa55aa);

            assertEquals(originalWord, original.getInt(8));
            assertNotEquals(original.getInt(8), copy.getInt(8));
            assertEquals(originalPosition, original.position());
            assertEquals(originalLimit, original.limit());
            assertEquals(original.order(), copy.order());
        } finally {
            if (copy != null) memFree(copy);
            memFree(original);
        }
    }

    @Test
    void canonicalMetalBackendLinksWithoutOptionalRenderMods() {
        assumeTrue(Boolean.getBoolean("metallum.test.noOptionalMods"));

        ClassLoader loader = MetalRenderPearlBoundaryTest.class.getClassLoader();
        assertThrows(ClassNotFoundException.class, () ->
                Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", false, loader));
        assertThrows(ClassNotFoundException.class, () ->
                Class.forName("net.irisshaders.iris.mixinterface.GpuTextureInterface", false, loader));

        for (String className : List.of(
                "com.metallum.client.metal.render.MetalBackend",
                "com.metallum.client.metal.render.MetalDevice",
                "com.metallum.client.metal.render.MetalGpuTexture",
                "com.metallum.client.metal.render.MetalGpuTextureView",
                "com.metallum.client.metal.render.MetalRenderPass",
                "com.metallum.client.metal.render.MetalCompiledRenderPipeline",
                "com.metallum.client.metal.render.MetalCrossShaderCompiler",
                "com.metallum.client.validation.MetalValidationClient"
        )) {
            Class<?> type = assertDoesNotThrow(() -> Class.forName(className, false, loader), className);
            assertDoesNotThrow(type::getDeclaredConstructors, className + " constructors");
            assertDoesNotThrow(type::getDeclaredMethods, className + " methods");
            assertDoesNotThrow(type::getDeclaredFields, className + " fields");
        }
    }

    @Test
    void vanillaMotionGateInitializesWithoutIrisBlendAbi() {
        assumeTrue(Boolean.getBoolean("metallum.test.noOptionalMods"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "net.irisshaders.iris.gl.blending.BlendModeOverride"));
        // Unlike signature-only linkage tests, this executes the class initializer
        // reached from MetalFxManager.beginFrame even with MetalFX disabled.
        assertTrue(IrisMetalPipelineOverrides.frameGenerationMotionSemanticsProven());
    }

    @Test
    void vanillaDepthStateDoesNotResolveOptionalIrisClass() throws Exception {
        assumeTrue(Boolean.getBoolean("metallum.test.noOptionalMods"));
        // The unit-test tree supplies its own Iris stub, so explicitly exclude it
        // to reproduce the production no-optional-mods class loader.
        String depthClass = MetalIrisDepthConvention.class.getName();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{
                MetalIrisDepthConvention.class.getProtectionDomain().getCodeSource().getLocation()
        }, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.irisshaders.")) {
                    throw new ClassNotFoundException(name);
                }
                if (name.equals(depthClass)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("net.irisshaders.iris.Iris", false, loader));
            var query = Class.forName(depthClass, true, loader).getDeclaredMethod("packInUseQuick");
            query.setAccessible(true);
            assertFalse((boolean) query.invoke(null));
        }
        assertEquals(0.0, MetalIrisDepthConvention.hardwareClear(0.0));
        assertEquals(com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN,
                MetalIrisDepthConvention.hardwareCompare(
                        com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN));
        assertEquals(2.0F, MetalIrisDepthConvention.hardwareDepthBias(2.0F));
    }
}
