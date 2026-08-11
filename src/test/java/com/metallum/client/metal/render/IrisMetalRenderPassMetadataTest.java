package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalRenderPassMetadataTest {
    @Test
    void mapsAttachmentActionsToNativePhysicalSlotOrder() {
        IrisMetalRenderPassMetadata metadata = IrisMetalRenderPassMetadata.from(List.of(
                attachment(4, 0, IrisMetalExecutionGraph.LoadAction.LOAD, IrisMetalExecutionGraph.StoreAction.STORE),
                attachment(1, 1, IrisMetalExecutionGraph.LoadAction.CLEAR, IrisMetalExecutionGraph.StoreAction.DISCARD),
                attachment(7, 2, IrisMetalExecutionGraph.LoadAction.DONT_CARE, IrisMetalExecutionGraph.StoreAction.STORE)
        ));

        assertEquals(3, metadata.colorCount());
        assertArrayEquals(new int[]{0, 1, 2}, metadata.nativeLoadActions(new int[]{0, 0, 0}));
        assertArrayEquals(new int[]{1, 1, 2}, metadata.nativeLoadActions(new int[]{1, 0, 0}));
        assertArrayEquals(new int[]{0, 1, 0}, metadata.nativeStoreActions());
        assertEquals(4, metadata.colorAttachments().get(0).logicalTarget());
        assertEquals(1, metadata.colorAttachments().get(1).physicalSlot());
    }

    @Test
    void metadataOwnsImmutableCopiesAndRejectsMalformedArrays() {
        IrisMetalRenderPassMetadata metadata = IrisMetalRenderPassMetadata.from(List.of(
                attachment(0, 0, IrisMetalExecutionGraph.LoadAction.LOAD, IrisMetalExecutionGraph.StoreAction.STORE)
        ));

        assertThrows(UnsupportedOperationException.class, () -> metadata.colorAttachments().clear());
        int[] loads = metadata.nativeLoadActions(new int[]{0});
        loads[0] = 99;
        assertArrayEquals(new int[]{0}, metadata.nativeLoadActions(new int[]{0}));
        assertThrows(IllegalArgumentException.class, () -> metadata.nativeLoadActions(new int[0]));
        assertThrows(NullPointerException.class, () -> metadata.nativeLoadActions(null));

        assertThrows(IllegalArgumentException.class, () -> IrisMetalRenderPassMetadata.from(List.of(
                attachment(0, 1, IrisMetalExecutionGraph.LoadAction.LOAD, IrisMetalExecutionGraph.StoreAction.STORE)
        )));
    }

    @Test
    void explicitClearIsIndependentOfPendingClearFlag() {
        IrisMetalRenderPassMetadata metadata = IrisMetalRenderPassMetadata.from(List.of(
                attachment(0, 0, IrisMetalExecutionGraph.LoadAction.CLEAR, IrisMetalExecutionGraph.StoreAction.STORE)
        ));

        assertArrayEquals(new int[]{1}, metadata.nativeLoadActions(new int[]{0}));
        assertArrayEquals(new int[]{1}, metadata.nativeLoadActions(new int[]{1}));
    }

    @Test
    void attachmentStateRejectsInvalidPhysicalState() {
        assertThrows(IllegalArgumentException.class, () -> attachment(0, -1,
                IrisMetalExecutionGraph.LoadAction.LOAD, IrisMetalExecutionGraph.StoreAction.STORE));
        assertThrows(IllegalArgumentException.class, () -> new IrisMetalExecutionGraph.AttachmentState(
                0, 0, GpuFormat.RGBA8_UNORM, Optional.empty(),
                ColorTargetState.WRITE_ALL | (1 << 8),
                IrisMetalExecutionGraph.LoadAction.LOAD,
                IrisMetalExecutionGraph.StoreAction.STORE
        ));
        assertThrows(IllegalArgumentException.class, () -> attachment(-1, 0,
                IrisMetalExecutionGraph.LoadAction.LOAD, IrisMetalExecutionGraph.StoreAction.STORE));
    }

    private static IrisMetalExecutionGraph.AttachmentState attachment(
            final int logicalTarget,
            final int physicalSlot,
            final IrisMetalExecutionGraph.LoadAction load,
            final IrisMetalExecutionGraph.StoreAction store
    ) {
        return new IrisMetalExecutionGraph.AttachmentState(
                logicalTarget,
                physicalSlot,
                GpuFormat.RGBA8_UNORM,
                Optional.empty(),
                ColorTargetState.WRITE_ALL,
                load,
                store
        );
    }

}
