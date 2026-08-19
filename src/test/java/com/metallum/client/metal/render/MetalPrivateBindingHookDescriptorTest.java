package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the bytecode call sites consumed by the P1c token-native redirectors.
 *
 * <p>Mixin's defaultRequire catches a stale redirect when a client applies the
 * mixin. This test moves that failure into ordinary {@code gradlew test}, where
 * dependency upgrades can name the exact Sodium/Iris call shape that changed.</p>
 */
final class MetalPrivateBindingHookDescriptorTest {
    private static final String RENDER_PASS = "com/mojang/blaze3d/systems/RenderPass";
    private static final String METAL_RENDER_PASS = "com/metallum/client/metal/render/MetalRenderPass";
    private static final String SET_UNIFORM_SLICE =
            "(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V";
    private static final String SET_UNIFORM_BUFFER =
            "(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V";
    private static final String BIND_TEXTURE =
            "(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;"
                    + "Lcom/mojang/blaze3d/textures/GpuSampler;)V";
    private static final String BIND_STORAGE_IMAGE =
            "(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;)V";

    @Test
    void sodiumTerrainStillHasTheFourFixedBindingCallSites() {
        ClassNode sodium = load("net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer");
        List<MethodNode> renderMethods = sodium.methods.stream()
                .filter(method -> method.name.equals("render"))
                .filter(method -> (method.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) == 0)
                .toList();
        assertEquals(1, renderMethods.size(),
                "DefaultChunkRenderer.render changed shape; review the P1c Sodium redirects");
        MethodNode render = renderMethods.getFirst();

        assertEquals(1, invocationCount(render, RENDER_PASS, "setUniform", SET_UNIFORM_SLICE),
                "u_Globals redirect no longer has exactly one GpuBufferSlice call site");
        assertEquals(1, invocationCount(render, RENDER_PASS, "setUniform", SET_UNIFORM_BUFFER),
                "u_SectionTimeInfo redirect no longer has exactly one GpuBuffer call site");
        assertEquals(2, invocationCount(render, RENDER_PASS, "bindTexture", BIND_TEXTURE),
                "u_LightTex/u_BlockTex ordinals are no longer the only two texture binding call sites");
    }

    @Test
    void irisRasterBindingLoopStillMatchesTheTokenCursorContract() {
        ClassNode postChain = load("com/metallum/client/metal/render/IrisMetalPostChain");
        List<MethodNode> methods = postChain.methods.stream()
                .filter(method -> method.name.equals("bindResources"))
                .toList();
        assertEquals(1, methods.size(),
                "IrisMetalPostChain.bindResources changed shape; review the token cursor before shipping");
        MethodNode bindResources = methods.getFirst();

        assertEquals(2, invocationCount(bindResources, METAL_RENDER_PASS, "setUniform", SET_UNIFORM_SLICE),
                "the token cursor expects one uniform-loop and one texel-buffer setUniform call site");
        assertEquals(1, invocationCount(bindResources, METAL_RENDER_PASS, "bindTexture", BIND_TEXTURE),
                "the token cursor expects exactly one sampled-texture binding call site");
        assertEquals(1, invocationCount(bindResources, METAL_RENDER_PASS, "bindStorageImage", BIND_STORAGE_IMAGE),
                "the token cursor expects exactly one storage-image binding call site");
    }

    private static int invocationCount(
            final MethodNode method,
            final String owner,
            final String name,
            final String descriptor
    ) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invoke
                    && invoke.owner.equals(owner)
                    && invoke.name.equals(name)
                    && invoke.desc.equals(descriptor)) {
                count++;
            }
        }
        return count;
    }

    private static ClassNode load(final String internalName) {
        String resource = internalName + ".class";
        ClassLoader loader = MetalPrivateBindingHookDescriptorTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError(resource + " is not on the test classpath");
            }
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException exception) {
            throw new AssertionError("Failed to inspect " + resource, exception);
        }
    }
}
