package com.metallum.client.metal.render;

import com.metallum.mixin.sodium.GlBufferArenaMetalLifetimeMixin;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GlBufferArenaMetalLifetimeTest {
    @Test
    void productionMixinTargetsSodiumArenaRetirementHook() throws Exception {
        Method sodiumRelease = GlBufferArena.class.getDeclaredMethod(
                "releaseBufferForReuse",
                GpuBuffer.class
        );
        assertTrue(Modifier.isPrivate(sodiumRelease.getModifiers()));
        assertTrue(Modifier.isStatic(sodiumRelease.getModifiers()));

        Method handler = GlBufferArenaMetalLifetimeMixin.class.getDeclaredMethod(
                "metallum$retireMetalArenaBacking",
                GpuBuffer.class,
                CallbackInfo.class
        );
        handler.setAccessible(true);

        CallbackInfo metalCallback = new CallbackInfo("releaseBufferForReuse", true);
        TestMetalGpuBuffer metalBuffer = new TestMetalGpuBuffer();
        handler.invoke(null, metalBuffer, metalCallback);
        assertTrue(metalCallback.isCancelled());
        assertTrue(metalBuffer.closedByHook);

        CallbackInfo foreignCallback = new CallbackInfo("releaseBufferForReuse", true);
        handler.invoke(null, new ForeignGpuBuffer(), foreignCallback);
        // A non-Metal backend must continue through Sodium's original static
        // free-list implementation instead of being intercepted by the
        // Metal-only lifecycle hook.
        assertFalse(foreignCallback.isCancelled());
    }

    private static final class TestMetalGpuBuffer extends MetalGpuBuffer {
        private boolean closedByHook;

        private TestMetalGpuBuffer() {
            // The wrapped-handle constructor does not touch the device. The
            // override below keeps this host test independent of MTLDevice.
            super(null, GpuBuffer.USAGE_VERTEX, 16L, MemorySegment.ofAddress(1L));
        }

        @Override
        public void close() {
            this.closedByHook = true;
        }
    }

    private static final class ForeignGpuBuffer extends GpuBuffer {
        private ForeignGpuBuffer() {
            super(GpuBuffer.USAGE_VERTEX, 16L);
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }

        @Override
        public GpuBufferSlice.MappedView map(
                final long offset,
                final long length,
                final boolean read,
                final boolean write
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
