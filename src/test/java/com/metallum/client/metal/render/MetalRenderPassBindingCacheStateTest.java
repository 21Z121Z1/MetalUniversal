package com.metallum.client.metal.render;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the private binding-state identity without requiring a Metal device. */
final class MetalRenderPassBindingCacheStateTest {
    @Test
    void sameSliceStopsMatchingAfterBackingVersionChanges() throws ReflectiveOperationException {
        Class<?> stateType = Class.forName(
                "com.metallum.mixin.render.MetalRenderPassBindingCacheMixin$BindingState"
        );
        Constructor<?> constructor = stateType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Method update = stateType.getDeclaredMethod("update", GpuBufferSlice.class);
        Method matches = stateType.getDeclaredMethod("matches", GpuBufferSlice.class);
        update.setAccessible(true);
        matches.setAccessible(true);

        VersionedBuffer buffer = new VersionedBuffer(64L);
        GpuBufferSlice slice = buffer.slice(8L, 16L);
        Object state = constructor.newInstance();

        update.invoke(state, slice);
        assertTrue((boolean) matches.invoke(state, slice));
        assertFalse((boolean) matches.invoke(state, buffer.slice(9L, 16L)));
        assertFalse((boolean) matches.invoke(state, buffer.slice(8L, 15L)));

        buffer.advanceBackingVersion();
        assertFalse((boolean) matches.invoke(state, slice),
                "same Java slice must not hide a different native backing generation");

        update.invoke(state, slice);
        assertTrue((boolean) matches.invoke(state, slice));
    }

    private static final class VersionedBuffer extends GpuBuffer implements MetalUploadDedupBuffer {
        private long bindingVersion;
        private boolean closed;

        private VersionedBuffer(final long size) {
            super(GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, size);
        }

        private void advanceBackingVersion() {
            this.bindingVersion++;
        }

        @Override
        public UploadRange metallum$diffUpload(final long offset, final ByteBuffer data) {
            return UploadRange.full(data.remaining());
        }

        @Override
        public long metallum$bindingVersion() {
            return this.bindingVersion;
        }

        @Override
        public boolean isClosed() {
            return this.closed;
        }

        @Override
        public void close() {
            this.closed = true;
        }

        @Override
        public GpuBufferSlice.MappedView map(
                final long offset,
                final long length,
                final boolean read,
                final boolean write
        ) {
            throw new UnsupportedOperationException("test buffer is not mappable");
        }
    }
}
