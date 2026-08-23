package com.metallum.client.metal.render;

import com.google.gson.JsonParser;
import com.metallum.client.validation.contract.PassType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalAttachmentLifetimeCompilerTest {
    @AfterEach
    void clearActivePlan() {
        IrisMetalExperimentalOptimizer.clear();
    }

    @Test
    void bindsThreeOrderedPingPongPassesAndComputesPhysicalLiveness() {
        IrisMetalOptimizationPlan plan = plan(false);
        IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt =
                IrisMetalAttachmentLifetimeCompiler.compile(plan, 7, inputs(false), bindings(100, 101));

        assertEquals("RESOLVED_CONSERVATIVE", receipt.status());
        assertEquals(3, receipt.attachments().size());
        assertEquals("alt", receipt.attachments().get(0).physicalSide());
        assertEquals("main", receipt.attachments().get(1).physicalSide());
        assertEquals("alt", receipt.attachments().get(2).physicalSide());
        assertEquals(1, receipt.attachments().get(1).passIndex());
        assertEquals(2, receipt.lifetimes().size());

        IrisMetalOptimizationPlan.AttachmentLifetime main = lifetime(receipt, 100);
        assertEquals(0, main.firstUse());
        assertEquals(1, main.lastWrite());
        assertEquals(2, main.nextUse());
        assertEquals("SAMPLED_READ", main.nextUseAccess());

        IrisMetalOptimizationPlan.AttachmentLifetime alt = lifetime(receipt, 101);
        assertEquals(0, alt.firstUse());
        assertEquals(2, alt.lastWrite());
        assertEquals(-1, alt.nextUse());
        assertNotEquals(main.allocationKey(), alt.allocationKey());
    }

    @Test
    void unresolvedDepthConsumerNeverLooksLikeTransientAttachment() {
        IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt =
                IrisMetalAttachmentLifetimeCompiler.compile(
                        plan(true), 7, inputs(true), bindings(100, 101)
                );

        assertEquals("UNRESOLVED_CONSERVATIVE", receipt.status());
        assertTrue(receipt.unresolvedConsumers().stream().anyMatch(value -> value.contains("depthtex0")));
        assertEquals(
                IrisMetalOptimizationPlan.AttachmentResolution.UNRESOLVED_CONSERVATIVE,
                receipt.attachments().getFirst().resolution()
        );
        assertTrue(receipt.attachments().stream().allMatch(attachment ->
                attachment.classification() == IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT));
    }

    @Test
    void rebindingAllocationsChangesPhysicalGenerationWithoutMutatingOldReceipt() {
        IrisMetalOptimizationPlan plan = plan(false);
        IrisMetalOptimizationPlan.AttachmentLifetimeReceipt oldReceipt =
                IrisMetalAttachmentLifetimeCompiler.compile(plan, 7, inputs(false), bindings(100, 101));
        IrisMetalOptimizationPlan.AttachmentLifetimeReceipt newReceipt =
                IrisMetalAttachmentLifetimeCompiler.compile(plan, 7, inputs(false), bindings(200, 201));

        assertNotEquals(oldReceipt.targetSignature(), newReceipt.targetSignature());
        assertEquals(100L, oldReceipt.attachments().get(1).allocationId());
        assertEquals(200L, newReceipt.attachments().get(1).allocationId());
        assertNotEquals(oldReceipt.lifetimes().getFirst().allocationKey(),
                newReceipt.lifetimes().getFirst().allocationKey());
        assertEquals(1L, oldReceipt.attachments().get(1).allocationGeneration());
        assertEquals(2L, newReceipt.attachments().get(1).allocationGeneration());
    }

    @Test
    void staleReceiptRetiresActivePhysicalBindingsWithoutMutatingPreviousReceipt() {
        IrisMetalOptimizationPlan plan = plan(false);
        IrisMetalOptimizationPlan.AttachmentLifetimeReceipt previous =
                IrisMetalAttachmentLifetimeCompiler.compile(plan, 7, inputs(false), bindings(100, 101));
        IrisMetalOptimizationPlan.AttachmentLifetimeReceipt stale =
                IrisMetalAttachmentLifetimeCompiler.staleReceipt(previous, 9L, "colortex0:main@200/2;");

        assertEquals("RESOLVED_CONSERVATIVE", previous.status());
        assertEquals("STALE_UNRESOLVED", stale.status());
        assertEquals(9L, stale.targetEpoch());
        assertTrue(stale.attachments().isEmpty());
        assertTrue(stale.unresolvedConsumers().contains("target-reallocated"));
        assertFalse(previous.attachments().isEmpty());
        assertEquals("colortex0:main@200/2;", stale.targetSignature());
    }

    @Test
    void unrelatedTargetStampCannotRetireActiveReceipt() {
        IrisMetalOptimizationPlan plan = plan(false);
        IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt =
                IrisMetalAttachmentLifetimeCompiler.compile(
                        plan, 7, 100L, inputs(false), bindings(100, 101)
                );

        assertFalse(IrisMetalOptimizationBootstrap.receiptBelongsToTargets(receipt, 999L));
        assertTrue(IrisMetalOptimizationBootstrap.receiptBelongsToTargets(receipt, 100L));
    }

    @Test
    void generationMismatchFailsClosedAndReceiptJsonIsDeterministic() {
        IrisMetalOptimizationPlan plan = plan(false);
        IrisMetalOptimizationPlan.AttachmentLifetimeReceipt mismatch =
                IrisMetalAttachmentLifetimeCompiler.compile(plan, 8, inputs(false), bindings(100, 101));
        assertEquals("UNRESOLVED_CONSERVATIVE", mismatch.status());
        assertTrue(mismatch.attachments().isEmpty());
        assertTrue(mismatch.unresolvedConsumers().contains("chain-generation-mismatch"));

        IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt =
                IrisMetalAttachmentLifetimeCompiler.compile(plan, 7, inputs(false), bindings(100, 101));
        String first = IrisMetalExperimentalOptimizer.toJson(
                IrisMetalOptimizationPlan.withAttachmentLifetimeReceipt(plan, receipt)
        );
        String second = IrisMetalExperimentalOptimizer.toJson(
                IrisMetalOptimizationPlan.withAttachmentLifetimeReceipt(plan, receipt)
        );
        assertEquals(first, second);
        assertEquals("UNRESOLVED_CONSERVATIVE", JsonParser.parseString(
                IrisMetalExperimentalOptimizer.toJson(
                        IrisMetalOptimizationPlan.withAttachmentLifetimeReceipt(plan, mismatch)
                )
        ).getAsJsonObject().getAsJsonObject("attachmentLifetimeReceipt")
                .get("status").getAsString());
    }

    private static IrisMetalOptimizationPlan.AttachmentLifetime lifetime(
            final IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt,
            final long allocationId
    ) {
        return receipt.lifetimes().stream()
                .filter(value -> value.allocationId() == allocationId)
                .findFirst()
                .orElseThrow();
    }

    private static List<IrisMetalAttachmentLifetimeCompiler.AllocationBinding> bindings(
            final long mainId,
            final long altId
    ) {
        return List.of(
                new IrisMetalAttachmentLifetimeCompiler.AllocationBinding(
                        0, "main", new MetalAllocationIdentity(mainId, mainId == 100 ? 1 : 2)
                ),
                new IrisMetalAttachmentLifetimeCompiler.AllocationBinding(
                        0, "alt", new MetalAllocationIdentity(altId, altId == 101 ? 1 : 2)
                )
        );
    }

    private static List<IrisMetalAttachmentLifetimeCompiler.RasterPassInput> inputs(
            final boolean unresolved
    ) {
        return List.of(
                input("a", 0, new BitSet(), unresolved ? Set.of("depthtex0") : Set.of("colortex0"),
                        unresolved),
                input("b", 1, bits(0), Set.of("colortex0"), false),
                input("c", 2, new BitSet(), Set.of("colortex0"), false)
        );
    }

    private static IrisMetalAttachmentLifetimeCompiler.RasterPassInput input(
            final String name,
            final int ordinal,
            final BitSet readsFromAlt,
            final Set<String> samplers,
            final boolean unresolved
    ) {
        String key = IrisMetalOptimizationPlan.stablePlanPassKey(
                "COMPOSITE", PassType.RENDER, ordinal, name
        );
        return new IrisMetalAttachmentLifetimeCompiler.RasterPassInput(
                key,
                new IrisMetalPostChain.PassInfo(
                        IrisMetalPostChain.Stage.COMPOSITE,
                        name,
                        new int[]{0},
                        readsFromAlt,
                        readsFromAlt,
                        new BitSet(),
                        samplers
                )
        );
    }

    private static IrisMetalOptimizationPlan plan(final boolean unresolved) {
        return IrisMetalExperimentalOptimizer.build(
                7,
                List.of(
                        descriptor("a", 0, false, unresolved),
                        descriptor("b", 1, true, false),
                        descriptor("c", 2, false, false)
                ),
                List.of(),
                List.of(),
                Set.of(),
                Set.of("colortex0")
        );
    }

    private static IrisMetalExperimentalOptimizer.PassDescriptor descriptor(
            final String name,
            final int ordinal,
            final boolean readsFromAlt,
            final boolean unresolved
    ) {
        BitSet reads = new BitSet();
        if (readsFromAlt) reads.set(0);
        List<IrisMetalHazardGraph.ResourceUse> uses = new java.util.ArrayList<>();
        uses.add(new IrisMetalHazardGraph.ResourceUse(
                unresolved ? "depthtex0" : "colortex0",
                IrisMetalHazardGraph.Access.SAMPLED_READ
        ));
        uses.add(new IrisMetalHazardGraph.ResourceUse(
                "colortex0", IrisMetalHazardGraph.Access.ATTACHMENT_WRITE
        ));
        return new IrisMetalExperimentalOptimizer.PassDescriptor(
                name,
                IrisMetalExperimentalOptimizer.PassDescriptor.Kind.RENDER,
                "COMPOSITE",
                ordinal,
                uses,
                false,
                List.of(new IrisMetalOptimizationPlan.AttachmentPolicy(
                        "colortex0",
                        readsFromAlt
                                ? IrisMetalOptimizationPlan.LoadAction.LOAD
                                : IrisMetalOptimizationPlan.LoadAction.DONT_CARE,
                        IrisMetalOptimizationPlan.StoreAction.STORE
                )),
                "[0]"
        );
    }

    private static BitSet bits(final int index) {
        BitSet bits = new BitSet();
        bits.set(index);
        return bits;
    }
}
