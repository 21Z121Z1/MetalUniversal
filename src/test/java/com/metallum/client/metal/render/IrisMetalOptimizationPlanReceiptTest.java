package com.metallum.client.metal.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metallum.client.validation.contract.PassType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalOptimizationPlanReceiptTest {
    @AfterEach
    void clearActivePlan() {
        IrisMetalExperimentalOptimizer.clear();
    }

    @Test
    void receiptRetainsGenerationAndSeparatesSameNamesByStageAndOrdinal() {
        IrisMetalOptimizationPlan plan = buildPlan(List.of(
                descriptor("BEGIN", 0, "Same Pass"),
                descriptor("COMPOSITE", 0, "Same Pass"),
                descriptor("COMPOSITE", 1, "Same Pass")
        ));

        assertEquals(17, plan.chainGeneration());
        assertEquals(3, plan.passReceipt().size());
        assertEquals("iris/begin/render/0/same-pass", plan.passReceipt().get(0).planPassKey());
        assertEquals("iris/composite/render/0/same-pass", plan.passReceipt().get(1).planPassKey());
        assertEquals("iris/composite/render/1/same-pass", plan.passReceipt().get(2).planPassKey());
        assertNotEquals(
                plan.passReceipt().get(1).semanticPassId(),
                plan.passReceipt().get(2).semanticPassId()
        );
        assertEquals("iris/composite/render/0/same-pass", plan.passReceipt().get(1).semanticPassId());
        assertEquals(PassType.RENDER, plan.passReceipt().get(1).type());
    }

    @Test
    void receiptFreezesLogicalUsesAndCandidatePoliciesWithoutPhysicalIdentity() {
        IrisMetalOptimizationPlan plan = buildPlan(List.of(
                descriptor("COMPOSITE", 0, "quoted \"pass\"")
        ));
        IrisMetalOptimizationPlan.PlanPass pass = plan.passReceipt().getFirst();

        assertEquals(
                IrisMetalHazardGraph.Access.SAMPLED_READ,
                pass.logicalUses().getFirst().access()
        );
        assertEquals(IrisMetalOptimizationPlan.LoadAction.LOAD,
                pass.attachmentCandidates().getFirst().load());
        assertEquals(IrisMetalOptimizationPlan.StoreAction.STORE,
                pass.attachmentCandidates().getFirst().store());
        assertEquals("[0]", pass.attachmentCompatibilityKey());
        assertEquals(
                IrisMetalOptimizationPlan.BindingStatus.UNBOUND_DIAGNOSTIC_ONLY,
                pass.bindingStatus()
        );

        assertThrows(UnsupportedOperationException.class, () -> plan.passReceipt().add(pass));
        assertThrows(UnsupportedOperationException.class, () -> pass.logicalUses().clear());
        assertThrows(UnsupportedOperationException.class, () -> pass.attachmentCandidates().clear());

        String json = IrisMetalExperimentalOptimizer.toJson(plan);
        assertTrue(json.contains("\"diagnosticOnly\": true"));
        assertTrue(json.contains("\"physicalIdentityBinding\": \"UNBOUND\""));
        assertTrue(json.contains("\"bindingStatus\": \"UNBOUND_DIAGNOSTIC_ONLY\""));
        assertFalse(json.contains("ResourceIdentity"));
        assertFalse(json.contains("runtimeId"));
        assertFalse(json.contains("nativeHandle"));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(17, root.get("chainGeneration").getAsInt());
        assertTrue(root.get("diagnosticOnly").getAsBoolean());
        assertEquals(1, root.getAsJsonArray("passes").size());
        assertEquals("iris/composite/render/0/quoted-pass",
                root.getAsJsonArray("passes").get(0).getAsJsonObject().get("planPassKey").getAsString());
    }

    @Test
    void repeatedBuildsEmitByteIdenticalReceiptJson() {
        List<IrisMetalExperimentalOptimizer.PassDescriptor> descriptors = List.of(
                descriptor("COMPOSITE", 0, "Second Pass"),
                descriptor("BEGIN", 0, "First Pass")
        );

        String first = IrisMetalExperimentalOptimizer.toJson(buildPlan(descriptors));
        String second = IrisMetalExperimentalOptimizer.toJson(buildPlan(new ArrayList<>(descriptors)));

        assertEquals(first, second);
    }

    @Test
    void duplicateStablePassKeyFailsBeforePublication() {
        List<IrisMetalExperimentalOptimizer.PassDescriptor> duplicate = List.of(
                descriptor("COMPOSITE", 0, "same"),
                descriptor("COMPOSITE", 0, "same")
        );

        assertThrows(IllegalArgumentException.class, () -> buildPlan(duplicate));
        assertTrue(IrisMetalExperimentalOptimizer.active() == null);
    }

    private static IrisMetalOptimizationPlan buildPlan(
            final List<IrisMetalExperimentalOptimizer.PassDescriptor> descriptors
    ) {
        return IrisMetalExperimentalOptimizer.build(
                17,
                descriptors,
                List.of(),
                List.of(),
                Set.of(),
                Set.of("colortex0")
        );
    }

    private static IrisMetalExperimentalOptimizer.PassDescriptor descriptor(
            final String stage,
            final int ordinal,
            final String name
    ) {
        return new IrisMetalExperimentalOptimizer.PassDescriptor(
                name,
                IrisMetalExperimentalOptimizer.PassDescriptor.Kind.RENDER,
                stage,
                ordinal,
                List.of(
                        new IrisMetalHazardGraph.ResourceUse(
                                "colortex0",
                                IrisMetalHazardGraph.Access.SAMPLED_READ
                        ),
                        new IrisMetalHazardGraph.ResourceUse(
                                "colortex1",
                                IrisMetalHazardGraph.Access.ATTACHMENT_WRITE
                        )
                ),
                false,
                List.of(new IrisMetalOptimizationPlan.AttachmentPolicy(
                        "colortex1",
                        IrisMetalOptimizationPlan.LoadAction.LOAD,
                        IrisMetalOptimizationPlan.StoreAction.STORE
                )),
                "[0]"
        );
    }
}
