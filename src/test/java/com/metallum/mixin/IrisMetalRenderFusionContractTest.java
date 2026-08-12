package com.metallum.mixin;

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

/** Pins Iris render-fusion state ownership to exception-safe stage/pass scopes. */
final class IrisMetalRenderFusionContractTest {
    private static final String POST_CHAIN =
            "com/metallum/client/metal/render/IrisMetalPostChain";
    private static final String FUSION_MIXIN =
            "com/metallum/mixin/iris/IrisMetalRenderFusionPolicyMixin";
    private static final String FUSION_RUNTIME =
            "com/metallum/client/metal/render/IrisMetalRenderFusionRuntime";
    private static final String OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String WRAP_METHOD =
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";

    private static final String EXECUTE_STAGE =
            "(Lcom/metallum/client/metal/render/IrisMetalPostChain$Stage;"
                    + "Lcom/metallum/client/metal/render/MetalDevice;"
                    + "Lcom/metallum/client/metal/render/IrisMetalRenderTargets;"
                    + "Lcom/metallum/client/metal/render/IrisMetalPostChain$ResourceProvider;)"
                    + "Lcom/metallum/client/metal/render/IrisMetalPostChain$ExecutionReceipt;";
    private static final String EXECUTE_PASS =
            "(Lcom/metallum/client/metal/render/MetalDevice;"
                    + "Lcom/metallum/client/metal/render/IrisMetalRenderTargets;"
                    + "Lcom/metallum/client/metal/render/IrisMetalPostChain$ResourceProvider;"
                    + "Lcom/metallum/client/metal/render/IrisMetalPostChain$PlannedPass;)V";

    @Test
    void stageWrapperClearsFusionStateOnEveryExit() throws IOException {
        assertNotNull(findMethod(readClass(POST_CHAIN), "executeStage", EXECUTE_STAGE),
                "IrisMetalPostChain changed the stage ABI; re-audit fusion ownership");

        MethodNode wrapper = requireMethod(
                readClass(FUSION_MIXIN),
                "metallum$executeStageWithCleanup",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "L" + OPERATION + ";)Ljava/lang/Object;"
        );
        assertWrapMethod(wrapper);
        assertTrue(!wrapper.tryCatchBlocks.isEmpty(),
                "stage fusion wrapper has no exceptional cleanup path");
        assertTrue(countCalls(wrapper, FUSION_RUNTIME, "breakChain", "()V") >= 2,
                "stage wrapper must clear fusion state at entry and on exit");
        assertTrue(countCalls(wrapper, OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;") == 1,
                "stage wrapper must invoke the original stage exactly once");
    }

    @Test
    void passWrapperPromotesOnlyCompletedPasses() throws IOException {
        assertNotNull(findMethod(readClass(POST_CHAIN), "executePass", EXECUTE_PASS),
                "IrisMetalPostChain changed the raster-pass ABI; re-audit fusion ownership");

        MethodNode wrapper = requireMethod(
                readClass(FUSION_MIXIN),
                "metallum$executeRasterPassWithCleanup",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "L" + OPERATION + ";)V"
        );
        assertWrapMethod(wrapper);
        assertTrue(!wrapper.tryCatchBlocks.isEmpty(),
                "raster-pass fusion wrapper has no exceptional cleanup path");
        assertTrue(countCalls(wrapper, FUSION_RUNTIME, "beginPass", "(Ljava/lang/Object;)V") >= 1,
                "raster-pass wrapper does not publish its pending signature");
        assertTrue(countCalls(wrapper, FUSION_RUNTIME, "endPass", "()V") >= 1,
                "successful raster passes are never promoted for fusion");
        assertTrue(countCalls(wrapper, FUSION_RUNTIME, "breakChain", "()V") >= 1,
                "failed raster passes do not invalidate stale fusion state");
        assertTrue(countCalls(wrapper, OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;") == 1,
                "raster-pass wrapper must invoke the original pass exactly once");
    }

    private static void assertWrapMethod(final MethodNode method) {
        assertTrue(method.visibleAnnotations != null
                        && method.visibleAnnotations.stream()
                        .anyMatch(annotation -> WRAP_METHOD.equals(annotation.desc)),
                method.name + " is not registered as a method wrapper");
    }

    private static ClassNode readClass(final String name) throws IOException {
        try (InputStream input = IrisMetalRenderFusionContractTest.class
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
