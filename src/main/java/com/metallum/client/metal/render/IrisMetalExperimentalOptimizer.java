package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.validation.contract.PassType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime holder for the experimental Iris optimization plan.
 *
 * <p>The planner is usable before any native path is enabled. Local debugging
 * can therefore dump and inspect the exact hazard/liveness/ABI decisions while
 * the renderer continues to execute the conservative path.</p>
 */
final class IrisMetalExperimentalOptimizer {
    record PassDescriptor(
            String name,
            Kind kind,
            String stage,
            int ordinal,
            List<IrisMetalHazardGraph.ResourceUse> uses,
            boolean explicitBarrierAfter,
            List<IrisMetalOptimizationPlan.AttachmentPolicy> attachments,
            String attachmentCompatibilityKey
    ) {
        enum Kind { RENDER, COMPUTE, COPY, PRESENT }

        PassDescriptor(
                final String name,
                final Kind kind,
                final List<IrisMetalHazardGraph.ResourceUse> uses,
                final boolean explicitBarrierAfter,
                final List<IrisMetalOptimizationPlan.AttachmentPolicy> attachments,
                final String attachmentCompatibilityKey
        ) {
            this(name, kind, "unknown", 0, uses, explicitBarrierAfter, attachments, attachmentCompatibilityKey);
        }

        PassDescriptor {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            stage = stage == null || stage.isBlank() ? "unknown" : stage;
            if (ordinal < 0) throw new IllegalArgumentException("Pass ordinal must be non-negative");
            uses = List.copyOf(uses);
            attachments = List.copyOf(attachments);
            attachmentCompatibilityKey = attachmentCompatibilityKey == null ? "" : attachmentCompatibilityKey;
        }

        PassType planType() {
            return switch (kind) {
                case RENDER -> PassType.RENDER;
                case COMPUTE -> PassType.COMPUTE;
                case COPY -> PassType.COPY;
                case PRESENT -> PassType.PRESENT;
            };
        }

        IrisMetalOptimizationPlan.PlanPass receipt() {
            return IrisMetalOptimizationPlan.passReceipt(
                    stage,
                    planType(),
                    ordinal,
                    name,
                    uses,
                    attachments,
                    attachmentCompatibilityKey
            );
        }
    }

    record ProgramDescriptor(
            String key,
            List<IrisMetalOptimizationPlan.ArgumentSlot> arguments
    ) {
        ProgramDescriptor {
            Objects.requireNonNull(key, "key");
            arguments = List.copyOf(arguments);
        }
    }

    record DrawDescriptor(
            String pipelineKey,
            String attachmentKey,
            boolean indexed
    ) {
        DrawDescriptor {
            Objects.requireNonNull(pipelineKey, "pipelineKey");
            Objects.requireNonNull(attachmentKey, "attachmentKey");
        }
    }

    private static final AtomicReference<IrisMetalOptimizationPlan> ACTIVE = new AtomicReference<>();

    private IrisMetalExperimentalOptimizer() {
    }

    static IrisMetalOptimizationPlan build(
            final int chainGeneration,
            final List<PassDescriptor> passes,
            final List<ProgramDescriptor> programs,
            final List<DrawDescriptor> draws,
            final Set<String> persistentResources,
            final Set<String> knownResources
    ) {
        IrisMetalHazardGraph.Builder hazards = IrisMetalHazardGraph.builder();
        for (PassDescriptor pass : passes) {
            String prefix = switch (pass.kind()) {
                case RENDER -> "render/";
                case COMPUTE -> "compute/";
                case COPY -> "copy/";
                case PRESENT -> "present/";
            };
            hazards.add(prefix + pass.name(), pass.uses(), pass.explicitBarrierAfter());
        }

        IrisMetalHazardGraph graph = hazards.build();
        IrisMetalOptimizationPlan.Builder plan = IrisMetalOptimizationPlan.builder(chainGeneration, graph);
        for (String resource : persistentResources) {
            plan.markPersistent(resource);
        }

        Set<String> observed = new LinkedHashSet<>();
        for (PassDescriptor pass : passes) {
            for (IrisMetalHazardGraph.ResourceUse use : pass.uses()) observed.add(use.resource());
        }
        for (String resource : knownResources) {
            if (!observed.contains(resource) && !persistentResources.contains(resource)) {
                plan.markDead(resource);
            }
        }

        for (int index = 0; index < passes.size(); index++) {
            PassDescriptor pass = passes.get(index);
            plan.passReceipt(pass.receipt());
            if (!pass.attachments().isEmpty()) {
                plan.attachmentPolicy(index, pass.attachments());
            }
        }

        for (ProgramDescriptor program : programs) {
            validateArgumentLayout(program);
            plan.argumentLayout(program.key(), IrisMetalOptimizationPlan.ArgumentLayout.of(program.arguments()));
        }

        deriveIndirectBatches(draws, plan);
        plan.deriveAdjacentMergeGroups();
        IrisMetalOptimizationPlan result = plan.build();
        validatePlan(passes, result);
        ACTIVE.set(result);
        maybeDump(result);
        return result;
    }

    static IrisMetalOptimizationPlan build(
            final List<PassDescriptor> passes,
            final List<ProgramDescriptor> programs,
            final List<DrawDescriptor> draws,
            final Set<String> persistentResources,
            final Set<String> knownResources
    ) {
        return build(0, passes, programs, draws, persistentResources, knownResources);
    }

    static IrisMetalOptimizationPlan active() {
        return ACTIVE.get();
    }

    static void clear() {
        ACTIVE.set(null);
    }

    static void publishAttachmentLifetimeReceipt(
            final IrisMetalOptimizationPlan plan,
            final IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(receipt, "receipt");
        ACTIVE.compareAndSet(plan, IrisMetalOptimizationPlan.withAttachmentLifetimeReceipt(plan, receipt));
    }

    private static void validateArgumentLayout(final ProgramDescriptor program) {
        Set<String> names = new LinkedHashSet<>();
        Map<IrisMetalOptimizationPlan.ArgumentSlot.Kind, Set<Integer>> indices = new LinkedHashMap<>();
        for (IrisMetalOptimizationPlan.ArgumentSlot slot : program.arguments()) {
            if (!names.add(slot.name())) {
                throw new IllegalStateException("Duplicate argument name in " + program.key() + ": " + slot.name());
            }
            if (!indices.computeIfAbsent(slot.kind(), ignored -> new LinkedHashSet<>()).add(slot.index())) {
                throw new IllegalStateException(
                        "Duplicate " + slot.kind() + " argument index in " + program.key() + ": " + slot.index()
                );
            }
        }
    }

    private static void deriveIndirectBatches(
            final List<DrawDescriptor> draws,
            final IrisMetalOptimizationPlan.Builder plan
    ) {
        int start = 0;
        while (start < draws.size()) {
            DrawDescriptor first = draws.get(start);
            int end = start + 1;
            while (end < draws.size()) {
                DrawDescriptor next = draws.get(end);
                if (!first.pipelineKey().equals(next.pipelineKey())
                        || !first.attachmentKey().equals(next.attachmentKey())
                        || first.indexed() != next.indexed()) {
                    break;
                }
                end++;
            }
            if (end - start > 1) {
                plan.indirectBatch(new IrisMetalOptimizationPlan.IndirectBatch(
                        first.pipelineKey(), first.attachmentKey(), start, end - start, first.indexed()
                ));
            }
            start = end;
        }
    }

    private static void validatePlan(
            final List<PassDescriptor> passes,
            final IrisMetalOptimizationPlan plan
    ) {
        for (IrisMetalOptimizationPlan.MergeGroup group : plan.renderMergeGroups()) {
            validateMergeGroup(passes, group, PassDescriptor.Kind.RENDER);
        }
        for (IrisMetalOptimizationPlan.MergeGroup group : plan.computeMergeGroups()) {
            validateMergeGroup(passes, group, PassDescriptor.Kind.COMPUTE);
        }
    }

    private static void validateMergeGroup(
            final List<PassDescriptor> passes,
            final IrisMetalOptimizationPlan.MergeGroup group,
            final PassDescriptor.Kind expectedKind
    ) {
        String attachmentKey = null;
        for (int index = group.firstNode(); index <= group.lastNode(); index++) {
            PassDescriptor pass = passes.get(index);
            if (pass.kind() != expectedKind) {
                throw new IllegalStateException("Merge group crosses pass kind boundary at " + pass.name());
            }
            if (expectedKind == PassDescriptor.Kind.RENDER) {
                if (attachmentKey == null) attachmentKey = pass.attachmentCompatibilityKey();
                if (!attachmentKey.equals(pass.attachmentCompatibilityKey())) {
                    throw new IllegalStateException(
                            "Render merge group crosses attachment signature at " + pass.name()
                    );
                }
            }
        }
    }

    private static void maybeDump(final IrisMetalOptimizationPlan plan) {
        String configured = System.getProperty("metallum.iris.experimental.planDump", "").trim();
        if (configured.isEmpty()) return;
        Path path = Path.of(configured);
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(
                    path,
                    toJson(plan),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException failure) {
            Metallum.LOGGER.warn("[metallum-iris] could not write optimization plan to {}", path, failure);
        }
    }

    static String toJson(final IrisMetalOptimizationPlan plan) {
        StringBuilder out = new StringBuilder(4096);
        out.append("{\n");
        appendFlag(out, "passFusion", IrisMetalOptimizationPlan.ENABLE_PASS_FUSION, true);
        appendFlag(out, "loadStoreLiveness", IrisMetalOptimizationPlan.ENABLE_LOAD_STORE, true);
        appendFlag(out, "computeGrouping", IrisMetalOptimizationPlan.ENABLE_COMPUTE_GROUPING, true);
        appendFlag(out, "resourcePruning", IrisMetalOptimizationPlan.ENABLE_RESOURCE_PRUNING, true);
        appendFlag(out, "finalColorFusion", IrisMetalOptimizationPlan.ENABLE_FINAL_COLOR_FUSION, true);
        appendFlag(out, "argumentTables", IrisMetalOptimizationPlan.ENABLE_ARGUMENT_TABLES, true);
        appendFlag(out, "icb", IrisMetalOptimizationPlan.ENABLE_ICB, true);
        appendFlag(out, "memorylessAttachments", IrisMetalOptimizationPlan.ENABLE_MEMORYLESS_ATTACHMENTS, true);
        appendFlag(out, "heapAliasing", IrisMetalOptimizationPlan.ENABLE_HEAP_ALIASING, true);
        out.append("  \"chainGeneration\": ").append(plan.chainGeneration()).append(",\n");
        out.append("  \"receiptStatus\": \"DIAGNOSTIC_ONLY\",\n");
        out.append("  \"diagnosticOnly\": true,\n");
        out.append("  \"bindingStatus\": \"")
                .append(IrisMetalOptimizationPlan.BindingStatus.UNBOUND_DIAGNOSTIC_ONLY.name())
                .append("\",\n");
        out.append("  \"physicalIdentityBinding\": \"UNBOUND\",\n");
        out.append("  \"nodes\": ").append(plan.hazards().nodes().size()).append(",\n");
        out.append("  \"edges\": ").append(plan.hazards().edges().size()).append(",\n");
        out.append("  \"renderMergeGroups\": ").append(plan.renderMergeGroups().size()).append(",\n");
        out.append("  \"computeMergeGroups\": ").append(plan.computeMergeGroups().size()).append(",\n");
        out.append("  \"deadResources\": ").append(jsonArray(plan.resourceLiveness().deadResources())).append(",\n");
        out.append("  \"depthtex1Required\": ").append(plan.resourceLiveness().depthtex1Required()).append(",\n");
        out.append("  \"depthtex2Required\": ").append(plan.resourceLiveness().depthtex2Required()).append(",\n");
        out.append("  \"argumentLayouts\": ").append(plan.argumentLayouts().size()).append(",\n");
        out.append("  \"indirectBatches\": ").append(plan.indirectBatches().size()).append(",\n");
        appendAttachmentLifetimeReceipt(out, plan.attachmentLifetimeReceipt());
        appendTransientResourcePlan(out, plan.attachmentLifetimeReceipt());
        appendHeapAliasRecipe(out, plan.attachmentLifetimeReceipt());
        out.append("  \"passes\": [\n");
        for (int index = 0; index < plan.passReceipt().size(); index++) {
            appendPlanPass(out, plan.passReceipt().get(index), index + 1 < plan.passReceipt().size());
        }
        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    private static void appendAttachmentLifetimeReceipt(
            final StringBuilder out,
            final IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt
    ) {
        out.append("  \"attachmentLifetimeReceipt\": ");
        if (receipt == null) {
            out.append("null,\n");
            return;
        }
        out.append("{\n");
        out.append("    \"chainGeneration\": ").append(receipt.chainGeneration()).append(",\n");
        out.append("    \"targetEpoch\": ").append(receipt.targetEpoch()).append(",\n");
        out.append("    \"targetSignature\": ").append(jsonString(receipt.targetSignature())).append(",\n");
        out.append("    \"status\": ").append(jsonString(receipt.status())).append(",\n");
        out.append("    \"unresolvedConsumers\": ").append(jsonStringArray(receipt.unresolvedConsumers())).append(",\n");
        out.append("    \"attachments\": [\n");
        for (int index = 0; index < receipt.attachments().size(); index++) {
            IrisMetalOptimizationPlan.ResolvedAttachment attachment = receipt.attachments().get(index);
            out.append("      {");
            out.append("\"planPassKey\":").append(jsonString(attachment.planPassKey())).append(",");
            out.append("\"semanticPassId\":").append(jsonString(attachment.semanticPassId())).append(",");
            out.append("\"slot\":").append(attachment.slot()).append(",");
            out.append("\"logicalResource\":").append(jsonString(attachment.logicalResource())).append(",");
            out.append("\"allocationId\":").append(attachment.allocationId()).append(",");
            out.append("\"allocationGeneration\":").append(attachment.allocationGeneration()).append(",");
            out.append("\"mipLevel\":").append(attachment.mipLevel()).append(",");
            out.append("\"physicalSide\":").append(jsonString(attachment.physicalSide())).append(",");
            out.append("\"load\":").append(jsonString(attachment.load().name())).append(",");
            out.append("\"store\":").append(jsonString(attachment.store().name())).append(",");
            out.append("\"passIndex\":").append(attachment.passIndex()).append(",");
            out.append("\"resolution\":").append(jsonString(attachment.resolution().name())).append(",");
            out.append("\"classification\":").append(jsonString(attachment.classification().name())).append(",");
            out.append("\"allocationKey\":").append(jsonString(attachment.allocationKey())).append(",");
            out.append("\"lifetime\":");
            appendLifetime(out, attachment.lifetime());
            out.append("}");
            if (index + 1 < receipt.attachments().size()) out.append(',');
            out.append('\n');
        }
        out.append("    ],\n");
        out.append("    \"lifetimes\": [\n");
        for (int index = 0; index < receipt.lifetimes().size(); index++) {
            appendLifetimeObject(out, receipt.lifetimes().get(index), "      ");
            if (index + 1 < receipt.lifetimes().size()) out.append(',');
            out.append('\n');
        }
        out.append("    ]\n");
        out.append("  },\n");
    }

    private static void appendTransientResourcePlan(
            final StringBuilder out,
            final IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt
    ) {
        out.append("  \"transientResourcePlan\": ");
        if (receipt == null) {
            out.append("null,\n");
            return;
        }
        IrisMetalTransientResourcePlan.Plan plan = IrisMetalTransientResourcePlan.compile(receipt);
        out.append("{\n");
        out.append("    \"chainGeneration\": ").append(plan.chainGeneration()).append(",\n");
        out.append("    \"targetEpoch\": ").append(plan.targetEpoch()).append(",\n");
        out.append("    \"status\": ").append(jsonString(plan.status())).append(",\n");
        out.append("    \"memorylessCount\": ").append(plan.memorylessCount()).append(",\n");
        out.append("    \"rejectionReasons\": ").append(jsonStringArray(plan.rejectionReasons())).append(",\n");
        out.append("    \"entries\": [");
        for (int index = 0; index < plan.entries().size(); index++) {
            IrisMetalTransientResourcePlan.Entry entry = plan.entries().get(index);
            if (index > 0) out.append(',');
            out.append("{\"resourceKey\":").append(jsonString(entry.resourceKey()))
                    .append(",\"allocationId\":").append(entry.allocationId())
                    .append(",\"allocationGeneration\":").append(entry.allocationGeneration())
                    .append(",\"firstUse\":").append(entry.firstUse())
                    .append(",\"lastUse\":").append(entry.lastUse())
                    .append(",\"load\":").append(jsonString(entry.load().name()))
                    .append(",\"store\":").append(jsonString(entry.store().name()))
                    .append(",\"allocationMode\":").append(jsonString(entry.allocationMode()))
                    .append(",\"decision\":").append(jsonString(entry.decision()))
                    .append(",\"reason\":").append(jsonString(entry.reason()))
                    .append('}');
        }
        out.append("]\n");
        out.append("  },\n");
    }

    /**
     * Emits the generation-owned placement-heap decision beside the transient
     * allocation plan.  This is deliberately a receipt, not a claim that a
     * native heap was allocated: the runtime publisher still requires the
     * feature gate and a fully resolved, generation-matched recipe.
     */
    private static void appendHeapAliasRecipe(
            final StringBuilder out,
            final IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt
    ) {
        out.append("  \"heapAliasRecipe\": ");
        if (receipt == null) {
            out.append("null,\n");
            return;
        }
        IrisMetalHeapAliasRecipe.Recipe recipe = IrisMetalHeapAliasRecipe.compile(receipt);
        out.append("{\n");
        out.append("    \"enabled\": ").append(IrisMetalOptimizationPlan.ENABLE_HEAP_ALIASING).append(",\n");
        out.append("    \"executable\": ").append(recipe.executable()).append(",\n");
        out.append("    \"chainGeneration\": ").append(recipe.chainGeneration()).append(",\n");
        out.append("    \"status\": ").append(jsonString(recipe.status())).append(",\n");
        out.append("    \"aliasSlotCount\": ").append(recipe.aliasSlots().size()).append(",\n");
        out.append("    \"aliasedResourceCount\": ").append(recipe.aliasedResourceCount()).append(",\n");
        out.append("    \"dedicatedCount\": ").append(recipe.dedicatedMembers().size()).append(",\n");
        out.append("    \"rejectionReasons\": ").append(jsonStringArray(recipe.rejectedReasons())).append(",\n");
        out.append("    \"slots\": [\n");
        for (int slotIndex = 0; slotIndex < recipe.aliasSlots().size(); slotIndex++) {
            IrisMetalHeapAliasRecipe.AliasSlot slot = recipe.aliasSlots().get(slotIndex);
            out.append("      {\"slotIndex\":").append(slot.slotIndex()).append(",\"members\":[");
            for (int memberIndex = 0; memberIndex < slot.members().size(); memberIndex++) {
                if (memberIndex > 0) out.append(',');
                IrisMetalHeapAliasRecipe.Member member = slot.members().get(memberIndex);
                out.append("{\"resourceKey\":").append(jsonString(member.resourceKey()))
                        .append(",\"sourceAllocationKey\":")
                        .append(jsonString(member.sourceAllocationKey()))
                        .append(",\"firstUse\":").append(member.firstUse())
                        .append(",\"lastUse\":").append(member.lastUse())
                        .append('}');
            }
            out.append("],\"handoffs\":[");
            for (int handoffIndex = 0; handoffIndex < slot.handoffs().size(); handoffIndex++) {
                if (handoffIndex > 0) out.append(',');
                IrisMetalHeapAliasRecipe.Handoff handoff = slot.handoffs().get(handoffIndex);
                out.append("{\"fromResourceKey\":")
                        .append(jsonString(handoff.fromResourceKey()))
                        .append(",\"toResourceKey\":")
                        .append(jsonString(handoff.toResourceKey()))
                        .append(",\"afterPass\":").append(handoff.afterPass())
                        .append(",\"beforePass\":").append(handoff.beforePass())
                        .append('}');
            }
            out.append("]}");
            if (slotIndex + 1 < recipe.aliasSlots().size()) out.append(',');
            out.append('\n');
        }
        out.append("    ]\n");
        out.append("  },\n");
    }

    private static void appendLifetime(final StringBuilder out,
                                       final IrisMetalOptimizationPlan.AttachmentLifetime lifetime) {
        if (lifetime == null) {
            out.append("null");
            return;
        }
        out.append('{');
        out.append("\"allocationKey\":").append(jsonString(lifetime.allocationKey())).append(',');
        out.append("\"allocationId\":").append(lifetime.allocationId()).append(',');
        out.append("\"allocationGeneration\":").append(lifetime.allocationGeneration()).append(',');
        out.append("\"mipLevel\":").append(lifetime.mipLevel()).append(',');
        out.append("\"firstUse\":").append(lifetime.firstUse()).append(',');
        out.append("\"lastWrite\":").append(lifetime.lastWrite()).append(',');
        out.append("\"lastUse\":").append(lifetime.lastUse()).append(',');
        out.append("\"nextUse\":").append(lifetime.nextUse()).append(',');
        out.append("\"nextUseAccess\":").append(jsonString(lifetime.nextUseAccess()));
        out.append('}');
    }

    private static void appendLifetimeObject(
            final StringBuilder out,
            final IrisMetalOptimizationPlan.AttachmentLifetime lifetime,
            final String indent
    ) {
        out.append(indent);
        appendLifetime(out, lifetime);
    }

    private static String jsonStringArray(final List<String> values) {
        List<String> escaped = new ArrayList<>();
        values.stream().sorted().forEach(value -> escaped.add(jsonString(value)));
        return "[" + String.join(",", escaped) + "]";
    }

    private static void appendPlanPass(
            final StringBuilder out,
            final IrisMetalOptimizationPlan.PlanPass pass,
            final boolean comma
    ) {
        out.append("    {\n");
        out.append("      \"planPassKey\": ").append(jsonString(pass.planPassKey())).append(",\n");
        out.append("      \"semanticPassId\": ").append(jsonString(pass.semanticPassId())).append(",\n");
        out.append("      \"passType\": ").append(jsonString(pass.type().name())).append(",\n");
        out.append("      \"stage\": ").append(jsonString(pass.stage())).append(",\n");
        out.append("      \"ordinal\": ").append(pass.ordinal()).append(",\n");
        out.append("      \"logicalUses\": [");
        for (int index = 0; index < pass.logicalUses().size(); index++) {
            IrisMetalHazardGraph.ResourceUse use = pass.logicalUses().get(index);
            if (index > 0) out.append(",");
            out.append("{\"resource\":")
                    .append(jsonString(use.resource()))
                    .append(",\"access\":")
                    .append(jsonString(use.access().name()))
                    .append("}");
        }
        out.append("],\n");
        out.append("      \"attachmentCandidates\": [");
        for (int index = 0; index < pass.attachmentCandidates().size(); index++) {
            IrisMetalOptimizationPlan.AttachmentPolicy policy = pass.attachmentCandidates().get(index);
            if (index > 0) out.append(",");
            out.append("{\"resource\":")
                    .append(jsonString(policy.resource()))
                    .append(",\"load\":")
                    .append(jsonString(policy.load().name()))
                    .append(",\"store\":")
                    .append(jsonString(policy.store().name()))
                    .append("}");
        }
        out.append("],\n");
        out.append("      \"attachmentCompatibilityKey\": ")
                .append(jsonString(pass.attachmentCompatibilityKey()))
                .append(",\n");
        out.append("      \"bindingStatus\": ")
                .append(jsonString(pass.bindingStatus().name()))
                .append("\n");
        out.append("    }");
        if (comma) out.append(",");
        out.append("\n");
    }

    private static void appendFlag(
            final StringBuilder out,
            final String name,
            final boolean value,
            final boolean comma
    ) {
        out.append("  \"").append(name).append("\": ").append(value);
        if (comma) out.append(',');
        out.append('\n');
    }

    private static String jsonArray(final Set<String> values) {
        List<String> escaped = new ArrayList<>();
        values.stream().sorted().forEach(value -> escaped.add(jsonString(value)));
        return "[" + String.join(",", escaped) + "]";
    }

    private static String jsonString(final String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(character);
            }
        }
        return escaped.append('"').toString();
    }
}
