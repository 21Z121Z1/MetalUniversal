package com.metallum.mixin;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Sodium bytecode contract used by {@code GlBufferArenaReuseFixMixin}.
 *
 * <p>The redirect is a correctness boundary, so a Sodium upgrade must fail a
 * local/CI test if {@code transferSegments} stops returning the old backing to
 * the process-wide reuse pool through the expected call site.</p>
 */
final class SodiumArenaReuseContractTest {
    private static final String ARENA_CLASS =
            "net/caffeinemc/mods/sodium/client/gpu/arena/GlBufferArena";
    private static final String BUFFER_DESCRIPTOR =
            "(Lcom/mojang/blaze3d/buffers/GpuBuffer;)V";

    @Test
    void protectionIsEnabledByDefaultAndOnlyLiteralFalseOptsOut() {
        String property = MetallumMixinConfigPlugin.SODIUM_ARENA_REUSE_PROTECTION_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            assertTrue(MetallumMixinConfigPlugin.sodiumArenaReuseProtectionConfigured());

            System.setProperty(property, "false");
            assertFalse(MetallumMixinConfigPlugin.sodiumArenaReuseProtectionConfigured());

            System.setProperty(property, "TRUE");
            assertTrue(MetallumMixinConfigPlugin.sodiumArenaReuseProtectionConfigured());

            System.setProperty(property, "misspelled");
            assertTrue(MetallumMixinConfigPlugin.sodiumArenaReuseProtectionConfigured());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void sodiumTransferSegmentsStillCallsTheRedirectedReuseMethod() throws IOException {
        ClassNode arena = readSodiumArenaClass();
        MethodNode transferSegments = findMethod(
                arena, "transferSegments", "(Ljava/util/Collection;J)V");
        assertNotNull(transferSegments,
                "Sodium changed GlBufferArena.transferSegments(Collection,long); audit the lifetime mixin");

        boolean invokesReuse = false;
        for (AbstractInsnNode instruction : transferSegments.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && ARENA_CLASS.equals(call.owner)
                    && "releaseBufferForReuse".equals(call.name)
                    && BUFFER_DESCRIPTOR.equals(call.desc)) {
                invokesReuse = true;
                break;
            }
        }
        assertTrue(invokesReuse,
                "Sodium no longer invokes the arena reuse call redirected by GlBufferArenaReuseFixMixin");

        MethodNode releaseBufferForReuse = findMethod(
                arena, "releaseBufferForReuse", BUFFER_DESCRIPTOR);
        assertNotNull(releaseBufferForReuse,
                "Sodium changed the arena reuse target; audit GlBufferArenaReuseFixMixin");
        assertTrue((releaseBufferForReuse.access & Opcodes.ACC_PRIVATE) != 0
                        && (releaseBufferForReuse.access & Opcodes.ACC_STATIC) != 0,
                "Sodium's arena reuse target is no longer private static; audit the redirect contract");
    }

    private static ClassNode readSodiumArenaClass() throws IOException {
        String resource = ARENA_CLASS + ".class";
        try (InputStream input = SodiumArenaReuseContractTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Sodium arena bytecode is missing from the test runtime classpath");
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode findMethod(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst()
                .orElse(null);
    }
}
