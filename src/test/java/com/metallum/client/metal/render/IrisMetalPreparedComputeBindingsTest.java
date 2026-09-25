package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class IrisMetalPreparedComputeBindingsTest {
    @Test
    void snapshotsPhysicalAllocationsInsteadOfShaderBindingNumbers() {
        MetalGpuBuffer a = opaqueBuffer(1);
        MetalGpuBuffer b = opaqueBuffer(2);
        var bindings = new IrisMetalPostChain.PreparedComputeBindings();
        bindings.buffer(0, a, 0, false);
        bindings.buffer(9, a, 16, true);
        bindings.buffer(2, b, 0, false);
        var access = bindings.accesses();
        assertEquals(Set.of(a.allocationIdentity(), b.allocationIdentity()), access.reads());
        assertEquals(Set.of(a.allocationIdentity()), access.writes());
        assertEquals(2, access.reads().size(), "two bindings of one allocation are still one physical dependency");
    }

    @Test
    void indirectOnlyAllocationParticipatesInReadAfterWriteAdmission() {
        MetalGpuBuffer args = opaqueBuffer(1);
        MetalGpuBuffer output = opaqueBuffer(2);
        var bindings = new IrisMetalPostChain.PreparedComputeBindings();
        bindings.buffer(0, output, 0, true);
        bindings.indirect(new IrisMetalPostChain.PreparedIndirectDispatch(args, 4, args.allocationIdentity()));
        var accesses = bindings.accesses();
        assertTrue(accesses.reads().contains(args.allocationIdentity()));
        assertFalse(accesses.writes().contains(args.allocationIdentity()));
        var window = new IrisMetalComputeGroupingRuntime.IndependenceWindow();
        window.append(new IrisMetalComputeGroupingRuntime.AccessSet(Set.of(), Set.of(args.allocationIdentity())));
        assertFalse(window.admits(accesses));
    }

    @Test
    void cannotSilentlyResampleABufferWhichChangesAfterPreparation() {
        MetalGpuBuffer buffer = opaqueBuffer(1);
        var bindings = new IrisMetalPostChain.PreparedComputeBindings();
        bindings.buffer(0, buffer, 0, true);
        MetalAllocationIdentity original = buffer.allocationIdentity();
        // No native allocation or dereference: this is the actual Java generation transition on opaque handles.
        buffer.swapBacking(MemorySegment.ofAddress(2), ByteBuffer.allocate(64));
        assertNotEquals(original, buffer.allocationIdentity());
        assertThrows(IllegalStateException.class, bindings::accesses,
                "the access proof must refer to the binding that will actually be encoded");
    }

    @Test
    void indirectBackingChangesAreRejectedEvenWhenNoShaderResourceUsesThem() {
        MetalGpuBuffer arguments = opaqueBuffer(1);
        var bindings = new IrisMetalPostChain.PreparedComputeBindings();
        bindings.indirect(new IrisMetalPostChain.PreparedIndirectDispatch(arguments, 0,
                arguments.allocationIdentity()));
        arguments.swapBacking(MemorySegment.ofAddress(2), ByteBuffer.allocate(64));
        assertThrows(IllegalStateException.class, bindings::accesses);
    }

    private static MetalGpuBuffer opaqueBuffer(long handle) {
        // Opaque identities are never submitted, dereferenced or released by these tests.
        return new MetalGpuBuffer(null, 0, 64, MemorySegment.ofAddress(handle));
    }
}
