package com.metallum.mixin;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the optional Iris compute-grouping cleanup boundary to the current Metal post-chain ABI. */
final class IrisMetalComputeGroupingContractTest {
    private static final String POST_CHAIN =
            "com/metallum/client/metal/render/IrisMetalPostChain";
    private static final String GROUPING_MIXIN =
            "com/metallum/mixin/iris/IrisMetalPostChainComputeGroupingMixin";
    private static final String GROUPING_RUNTIME =
            "com/metallum/client/metal/render/IrisMetalComputeGroupingRuntime";
    private static final String WRAP_OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String WRAP_METHOD_ANNOTATION =
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";
    private static final String COMPUTE_GROUP =
            "(Lcom/metallum/client/metal/render/MetalDevice;"
                    + "Lcom/metallum/client/metal/render/IrisMetalRenderTargets;"
                    + "Lcom/metallum/client/metal/render/IrisMetalPostChain$ResourceProvider;"
                    + "Ljava/util/List;Ljava/util/List;)V";

    @Test
    void wrapperMatchesPrivateComputeGroupAndHasExceptionalAbort() throws IOException {
        assertNotNull(findMethod(readClass(POST_CHAIN), "executeComputeGroup", COMPUTE_GROUP),
                "IrisMetalPostChain changed the compute-group method ABI; re-audit the wrapper");

        MethodNode wrapper = requireMethod(
                readClass(GROUPING_MIXIN),
                "metallum$executeComputeGroupWithCleanup",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/util/List;Ljava/util/List;L" + WRAP_OPERATION + ";)V"
        );
        assertTrue(wrapper.visibleAnnotations != null
                        && wrapper.visibleAnnotations.stream()
                        .anyMatch(annotation -> WRAP_METHOD_ANNOTATION.equals(annotation.desc)),
                "compute grouping cleanup is not registered as a method wrapper");
        assertEquals(2, countCalls(wrapper, GROUPING_RUNTIME, "abort", "()V"),
                "normal and exceptional finally paths must both clear grouping state");
        assertEquals(1, countCalls(wrapper, GROUPING_RUNTIME, "begin", "(Ljava/util/List;Z)Z"));
        assertEquals(1, countCalls(wrapper, WRAP_OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;"));
        assertTrue(!wrapper.tryCatchBlocks.isEmpty(),
                "compute grouping wrapper has no exception handler; stale encoder reuse could leak");
    }

    private static ClassNode readClass(final String name) throws IOException {
        try (InputStream input = IrisMetalComputeGroupingContractTest.class
                .getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name + " bytecode is missing from the test runtime classpath");
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode requireMethod(
            final ClassNode owner,
            final String name,
            final String descriptor
    ) {
        MethodNode method = findMethod(owner, name, descriptor);
        assertNotNull(method, owner.name + "." + name + descriptor + " is missing");
        return method;
    }

    private static MethodNode findMethod(
            final ClassNode owner,
            final String name,
            final String descriptor
    ) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst()
                .orElse(null);
    }

    private static int countCalls(
            final MethodNode method,
            final String owner,
            final String name,
            final String descriptor
    ) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }
}
