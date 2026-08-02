package com.metallum.client.metal.render;

import com.metallum.Metallum;

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
            List<IrisMetalHazardGraph.ResourceUse> uses,
            boolean explicitBarrierAfter,
            List<IrisMetalOptimizationPlan.AttachmentPolicy> attachments,
            String attachmentCompatibilityKey
    ) {
        enum Kind { RENDER, COMPUTE, COPY, PRESENT }

        PassDescriptor {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            uses = List.copyOf(uses);
            attachments = List.copyOf(attachments);
            attachmentCompatibilityKey = attachmentCompatibilityKey == null ? "" : attachmentCompatibilityKey;
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
        IrisMetalOptimizationPlan.Builder plan = IrisMetalOptimizationPlan.builder(graph);
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

    static IrisMetalOptimizationPlan active() {
        return ACTIVE.get();
    }

    static void clear() {
        ACTIVE.set(null);
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
        out.append("  \"nodes\": ").append(plan.hazards().nodes().size()).append(",\n");
        out.append("  \"edges\": ").append(plan.hazards().edges().size()).append(",\n");
        out.append("  \"renderMergeGroups\": ").append(plan.renderMergeGroups().size()).append(",\n");
        out.append("  \"computeMergeGroups\": ").append(plan.computeMergeGroups().size()).append(",\n");
        out.append("  \"deadResources\": ").append(jsonArray(plan.resourceLiveness().deadResources())).append(",\n");
        out.append("  \"depthtex1Required\": ").append(plan.resourceLiveness().depthtex1Required()).append(",\n");
        out.append("  \"depthtex2Required\": ").append(plan.resourceLiveness().depthtex2Required()).append(",\n");
        out.append("  \"argumentLayouts\": ").append(plan.argumentLayouts().size()).append(",\n");
        out.append("  \"indirectBatches\": ").append(plan.indirectBatches().size()).append("\n");
        out.append("}\n");
        return out.toString();
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
        for (String value : values) {
            escaped.add("\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        }
        return "[" + String.join(",", escaped) + "]";
    }
}
