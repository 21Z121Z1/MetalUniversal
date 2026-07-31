package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;

import java.util.BitSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class IrisMetalExecutionGraphTest {
    @Test
    void drawBuffersFlipBeforeExplicitTrueFlip() {
        BitSet before = new BitSet();
        IrisMetalExecutionGraph.FlipTransition transition = IrisMetalExecutionGraph.transition(
                before, new int[]{0}, Map.of(0, true), 2
        );

        assertEquals(new BitSet(), transition.readsFromAlt());
        assertEquals(new BitSet(), transition.stateAfter(), "the two toggles cancel");
    }

    @Test
    void explicitFalseSuppressesImplicitDrawBuffersFlip() {
        IrisMetalExecutionGraph.FlipTransition transition = IrisMetalExecutionGraph.transition(
                new BitSet(), new int[]{1}, Map.of(1, false), 2
        );

        assertEquals(new BitSet(), transition.stateAfter());
    }

    @Test
    void invalidAndRepeatedDrawBuffersFailClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> IrisMetalExecutionGraph.validateDrawBuffers("bad", new int[]{2}, 2)
        );
        assertThrows(
                IllegalStateException.class,
                () -> IrisMetalExecutionGraph.validateDrawBuffers("duplicate", new int[]{0, 0}, 2)
        );
    }

    @Test
    void explicitFlipTargetRangeIsStrict() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalExecutionGraph.transition(
                        new BitSet(), new int[]{0}, Map.of(2, true), 2
                )
        );
    }

    @Test
    void transitionAccumulatesHistoryBeforeApplyingTheNextPass() {
        BitSet before = new BitSet();
        before.set(0);
        BitSet historyBefore = new BitSet();
        historyBefore.set(1);

        IrisMetalExecutionGraph.FlipTransition transition = IrisMetalExecutionGraph.transition(
                before, historyBefore, new int[]{0}, Map.of(0, true), 3
        );

        assertEquals(before, transition.readsFromAlt());
        assertEquals(before, transition.stateAfter(), "implicit and explicit flips cancel");
        assertEquals(Set.of(0, 1), bitSetValues(transition.flippedAtLeastOnceAfter()));
        assertEquals(Set.of(1), bitSetValues(historyBefore), "transition must not mutate its input");
    }

    @Test
    void preFlipsChangeTheInputSnapshotButDoNotCreatePassHistory() {
        BitSet afterPreFlip = new BitSet();
        IrisMetalExecutionGraph.applyPreFlips(afterPreFlip, Map.of(0, true), 2);
        IrisMetalExecutionGraph.FlipTransition transition = IrisMetalExecutionGraph.transition(
                afterPreFlip, new BitSet(), new int[0], Map.of(), 2
        );

        assertEquals(Set.of(0), bitSetValues(transition.readsFromAlt()));
        assertEquals(Set.of(0), bitSetValues(transition.stateAfter()));
        assertEquals(Set.of(), bitSetValues(transition.flippedAtLeastOnceAfter()));
    }

    @Test
    void finalHistoryExcludesTargetsClearedEveryFrame() {
        BitSet finalSnapshot = new BitSet();
        finalSnapshot.set(0);
        finalSnapshot.set(1);

        assertEquals(
                Set.of(0),
                IrisMetalExecutionGraph.finalHistoryTargets(finalSnapshot, Set.of(1), 2)
        );
    }

    @Test
    void finalHistoryRejectsOutOfRangeSnapshotBits() {
        BitSet finalSnapshot = new BitSet();
        finalSnapshot.set(2);
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalExecutionGraph.finalHistoryTargets(finalSnapshot, Set.of(), 2)
        );
    }

    @Test
    void attachmentContractKeepsLogicalAndPhysicalIdentitySeparate() {
        IrisMetalExecutionGraph.AttachmentState attachment = new IrisMetalExecutionGraph.AttachmentState(
                4,
                1,
                GpuFormat.RGBA8_UNORM,
                Optional.empty(),
                ColorTargetState.WRITE_RED,
                IrisMetalExecutionGraph.LoadAction.LOAD,
                IrisMetalExecutionGraph.StoreAction.STORE
        );

        assertEquals(4, attachment.logicalTarget());
        assertEquals(1, attachment.physicalSlot());
        assertEquals(GpuFormat.RGBA8_UNORM, attachment.format());
        assertEquals(ColorTargetState.WRITE_RED, attachment.writeMask());
    }

    private static Set<Integer> bitSetValues(final BitSet bits) {
        Set<Integer> result = new java.util.LinkedHashSet<>();
        for (int value = bits.nextSetBit(0); value >= 0; value = bits.nextSetBit(value + 1)) {
            result.add(value);
        }
        return result;
    }

    @Test
    void rasterStorageBindingsKeepLogicalSsboIdentity() {
        String descriptor = MetalCrossShaderCompiler.storageBufferDescriptorName(7, "voxelData");
        assertEquals("iris_ssbo/7/voxelData", descriptor);
        assertEquals(7, MetalCrossShaderCompiler.storageBufferLogicalBinding(descriptor));
        assertEquals(-1, MetalCrossShaderCompiler.storageBufferLogicalBinding("voxelData"));
    }

    @Test
    void linkerDistinguishesStorageImagesFromSampledSamplers() {
        assertEquals(
                true,
                new IrisMetalGlslLinker.SamplerDecl("lightimg0", "image3D").storageImage()
        );
        assertEquals(
                false,
                new IrisMetalGlslLinker.SamplerDecl("voxeltex", "sampler3D").storageImage()
        );
    }
}
