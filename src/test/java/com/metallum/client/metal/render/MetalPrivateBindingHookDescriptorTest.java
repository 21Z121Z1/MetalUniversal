package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the bytecode call sites consumed by the P1c token-native redirectors.
 *
 * <p>Mixin's defaultRequire catches a stale redirect when a client applies the
 * mixin. This test moves that failure into ordinary {@code gradlew test}, where
 * dependency upgrades can name the exact Sodium/Iris call shape that changed.
 * The Sodium checks also pin the literal resource names: the private path uses
 * pre-resolved tokens, so retaining a call descriptor while renaming a resource
 * must fail here instead of pairing an old token with a new compatibility key.</p>
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
    private static final String MULTI_DRAW_BATCH =
            "net/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch";
    private static final String DRAW_CONTEXT =
            "(Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;)V";
    private static final String VK_INDIRECT_BATCH =
            "net/caffeinemc/mods/sodium/client/gpu/device/batch/VKIndirectDrawBatch";
    private static final String DRAW_INDEXED_INDIRECT =
            "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)V";

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

        assertEquals(List.of("u_Globals"),
                invocationStringKeys(render, RENDER_PASS, "setUniform", SET_UNIFORM_SLICE),
                "the GpuBufferSlice terrain binding is no longer the fixed u_Globals resource");
        assertEquals(List.of("u_SectionTimeInfo"),
                invocationStringKeys(render, RENDER_PASS, "setUniform", SET_UNIFORM_BUFFER),
                "the GpuBuffer terrain binding is no longer the fixed u_SectionTimeInfo resource");
        assertEquals(List.of("u_LightTex", "u_BlockTex"),
                invocationStringKeys(render, RENDER_PASS, "bindTexture", BIND_TEXTURE),
                "the two texture ordinals no longer map to u_LightTex then u_BlockTex");
    }

    @Test
    void sodiumTerrainProducerStillHasOneBatchDrawBoundary() {
        ClassNode sodium = load("net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer");
        MethodNode render = sodium.methods.stream()
                .filter(method -> method.name.equals("render"))
                .filter(method -> (method.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) == 0)
                .findFirst()
                .orElseThrow();

        assertEquals(1, invocationCount(render, MULTI_DRAW_BATCH, "draw", DRAW_CONTEXT),
                "terrain snapshot scope must wrap Sodium's single producer batch call");
    }

    @Test
    void sodiumIndirectProducerStillOwnsTheSingleUploadedCommandCall() {
        ClassNode indirect = load(VK_INDIRECT_BATCH);
        MethodNode draw = indirect.methods.stream()
                .filter(method -> method.name.equals("draw"))
                .filter(method -> method.desc.contains("DrawContext"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, invocationCount(draw, RENDER_PASS, "drawIndexedIndirect", DRAW_INDEXED_INDIRECT),
                "VKIndirectDrawBatch.draw must keep one RenderPass indirect submission");
        FieldNode commands = indirect.fields.stream()
                .filter(field -> field.name.equals("pCommands"))
                .findFirst()
                .orElseThrow();
        assertEquals("J", commands.desc);
        assertTrue((commands.access & Opcodes.ACC_PRIVATE) != 0);
        assertTrue((commands.access & Opcodes.ACC_FINAL) != 0);

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

    private static List<String> invocationStringKeys(
            final MethodNode method,
            final String owner,
            final String name,
            final String descriptor
    ) {
        List<String> keys = new ArrayList<>();
        String lastStringConstant = null;
        for (var instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                lastStringConstant = value;
            }
            if (instruction instanceof MethodInsnNode invoke
                    && invoke.owner.equals(owner)
                    && invoke.name.equals(name)
                    && invoke.desc.equals(descriptor)) {
                if (lastStringConstant == null) {
                    throw new AssertionError(
                            "binding invocation " + owner + "." + name + descriptor
                                    + " no longer has a preceding String constant"
                    );
                }
                keys.add(lastStringConstant);
                lastStringConstant = null;
            }
        }
        return keys;
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
