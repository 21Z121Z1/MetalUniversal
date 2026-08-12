package com.metallum.client.metal.render;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the fail-closed render-pass state shadow and duplicate suppression rules. */
final class MetalRenderPassStateShadowContractTest {
    private static final String PASS = "com/metallum/client/metal/render/MetalRenderPass";

    @Test
    void textureUnbindInvalidatesNativeDescriptorState() throws IOException {
        MethodNode method = requireMethod(readClass(PASS), "bindTexture");
        assertTrue(countCalls(method, "java/util/HashMap", "remove") >= 1,
                "texture unbind must remove the Java shadow binding");
        assertTrue(countCalls(method, PASS, "markDescriptorDirty") >= 1,
                "texture unbind/change must invalidate the native descriptor state");
        assertTrue(countCalls(method,
                        "com/metallum/client/metal/render/MetalCommandEncoder",
                        "flushPendingClear") >= 1,
                "rebinding the same texture must still materialize a pending clear");
        assertTrue(countCalls(method, "java/util/HashMap", "get") >= 1,
                "texture binding must compare against the existing shadow before allocating a replacement record");
    }

    @Test
    void repeatedBufferSlicesUseTheExistingSliceIdentityGate() throws IOException {
        ClassNode pass = readClass(PASS);
        MethodNode storage = requireMethod(pass, "bindStorageBuffer");
        MethodNode uniform = requireMethod(pass, "setUniform", 2);

        assertTrue(countCalls(storage, PASS, "sameSlice") >= 1,
                "storage-buffer mutations must suppress exact duplicate slices before descriptor scanning");
        assertTrue(countCalls(uniform, PASS, "sameSlice") >= 1,
                "uniform mutations must suppress exact duplicate direct bindings");
        assertTrue(countCalls(uniform, PASS, "markDescriptorDirty") >= 2,
                "uniform mutations must retain the Iris aggregate-block invalidation path even for identical slices");
    }

    private static ClassNode readClass(final String name) throws IOException {
        try (InputStream input = MetalRenderPassStateShadowContractTest.class
                .getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name + " bytecode is missing from the test runtime classpath");
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode requireMethod(final ClassNode owner, final String name) {
        return requireMethod(owner, name, -1);
    }

    private static MethodNode requireMethod(final ClassNode owner, final String name, final int parameterCount) {
        MethodNode method = owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name))
                .filter(candidate -> parameterCount < 0 || org.objectweb.asm.Type.getArgumentTypes(candidate.desc).length == parameterCount)
                .findFirst()
                .orElse(null);
        assertNotNull(method, owner.name + "." + name + " is missing");
        return method;
    }

    private static int countCalls(final MethodNode method, final String owner, final String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}
