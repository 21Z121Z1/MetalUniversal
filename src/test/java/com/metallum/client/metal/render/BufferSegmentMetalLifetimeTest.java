package com.metallum.client.metal.render;

import com.metallum.mixin.sodium.GlBufferSegmentGenerationMixin;
import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural contract for the Sodium 0.9.2 / Minecraft 26.3 allocation seam.
 *
 * <p>26.3 no longer has {@code GlBufferArena.releaseBufferForReuse}; the
 * authoritative lifecycle is {@link BufferSegment}'s nullable owner and
 * protected mutation methods. The actual generation field is installed by
 * Fabric mixin application in a client, so this unit test verifies the exact
 * target and hook ABI without pretending that an untransformed unit-test JVM
 * is a running Minecraft client.</p>
 */
final class BufferSegmentMetalLifetimeTest {
    @Test
    void generationMixinTargetsThe26_3BufferSegmentLifecycle() throws Exception {
        // Mixin metadata has CLASS retention and is not visible to the
        // untransformed unit-test JVM. Fabric's mixin processor validates the
        // annotation when the client is launched, so this test checks the
        // declared hook ABI instead.
        assertHook(GlBufferSegmentGenerationMixin.class, "metallum$isFree");
        assertHook(GlBufferSegmentGenerationMixin.class, "metallum$generation");

        assertHook(BufferSegment.class, "setFree");
        assertHook(BufferSegment.class, "setOwner",
                net.caffeinemc.mods.sodium.client.gpu.arena.RegionAllocatorHandle.class, int.class);
        assertHook(BufferSegment.class, "setOffset", long.class);
        assertHook(BufferSegment.class, "setLength", long.class);
        assertPresent(BufferSegment.class, "getOffset");
        assertPresent(BufferSegment.class, "getLength");
    }

    @Test
    void unowned26_3SegmentIsTheFreeState() throws Exception {
        BufferSegment segment = new BufferSegment(null, null, 0, 16L, 32L);
        Method isFree = BufferSegment.class.getDeclaredMethod("isFree");
        isFree.setAccessible(true);

        assertEquals(16L, segment.getOffset());
        assertEquals(32L, segment.getLength());
        assertTrue((Boolean) isFree.invoke(segment));
    }

    private static void assertHook(
            final Class<?> owner,
            final String name,
            final Class<?>... parameterTypes
    ) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        if (owner == BufferSegment.class) {
            assertTrue(Modifier.isProtected(method.getModifiers()), name + " must remain a protected Sodium seam");
        }
    }

    private static void assertPresent(final Class<?> owner, final String name) throws Exception {
        owner.getDeclaredMethod(name);
    }
}