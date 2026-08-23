package com.metallum.client.metal.render;

import com.metallum.client.validation.contract.PassType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stage B diagnostic compiler for raster attachment identity and liveness.
 *
 * <p>This class only binds the already-built logical plan to live renderer
 * allocations. It does not change a render descriptor, load/store action,
 * encoder boundary, or allocation policy.</p>
 */
final class IrisMetalAttachmentLifetimeCompiler {
    private static final String UNRESOLVED_KEY = "unresolved";

    private IrisMetalAttachmentLifetimeCompiler() {
    }

    record AllocationBinding(
            int logicalTarget,
            String physicalSide,
            MetalAllocationIdentity identity
    ) {
        AllocationBinding {
            if (logicalTarget < 0) {
                throw new IllegalArgumentException("Logical target must be non-negative");
            }
            Objects.requireNonNull(physicalSide, "physicalSide");
            Objects.requireNonNull(identity, "identity");
        }

        String allocationKey() {
            return "allocation/" + identity.allocationId()
                    + "/generation/" + identity.generation() + "/mip/0";
        }
    }

    record RasterPassInput(String planPassKey, IrisMetalPostChain.PassInfo info) {
        RasterPassInput {
            Objects.requireNonNull(planPassKey, "planPassKey");
            Objects.requireNonNull(info, "info");
        }
    }

    private record Event(int passIndex, IrisMetalHazardGraph.Access access) {
    }

    private record Candidate(
            String planPassKey,
            String semanticPassId,
            int slot,
            String logicalResource,
            int passIndex,
            String physicalSide,
            IrisMetalOptimizationPlan.LoadAction load,
            IrisMetalOptimizationPlan.StoreAction store,
            AllocationBinding binding,
            boolean unresolved
    ) {
    }

    static IrisMetalOptimizationPlan.AttachmentLifetimeReceipt compile(
            final IrisMetalOptimizationPlan plan,
            final IrisMetalPostChain chain,
            final IrisMetalRenderTargets targets
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(targets, "targets");
        List<AllocationBinding> bindings = new ArrayList<>();
        for (int logical = 0; logical < targets.colorTargets().targetCount(); logical++) {
            bindings.add(new AllocationBinding(
                    logical, "main", targets.colorTargets().mainTexture(logical).allocationIdentity()
            ));
            bindings.add(new AllocationBinding(
                    logical, "alt", targets.colorTargets().altTexture(logical).allocationIdentity()
            ));
        }
        return compile(plan, chain, targets.allocationStamp(), bindings);
    }

    /** Pure compiler entry used by focused tests and by the live-target adapter above. */
    static IrisMetalOptimizationPlan.AttachmentLifetimeReceipt compile(
            final IrisMetalOptimizationPlan plan,
            final IrisMetalPostChain chain,
            final List<AllocationBinding> bindings
    ) {
        return compile(plan, chain, 0L, bindings);
    }

    static IrisMetalOptimizationPlan.AttachmentLifetimeReceipt compile(
            final IrisMetalOptimizationPlan plan,
            final IrisMetalPostChain chain,
            final long targetEpoch,
            final List<AllocationBinding> bindings
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(bindings, "bindings");
        String targetSignature = targetSignature(bindings);
        if (plan.chainGeneration() != chain.generation()) {
            return unresolvedReceipt(
                    plan.chainGeneration(), targetEpoch, targetSignature, "chain-generation-mismatch"
            );
        }
        List<RasterPassInput> passInputs = new ArrayList<>();
        for (IrisMetalPostChain.Stage stage : IrisMetalPostChain.Stage.values()) {
            List<IrisMetalPostChain.PassInfo> infos = chain.passInfos(stage);
            for (int ordinal = 0; ordinal < infos.size(); ordinal++) {
                IrisMetalPostChain.PassInfo info = infos.get(ordinal);
                passInputs.add(new RasterPassInput(
                        IrisMetalOptimizationPlan.stablePlanPassKey(
                                stage.name(), PassType.RENDER, ordinal, info.name()
                        ),
                        info
                ));
            }
        }
        return compile(plan, chain.generation(), targetEpoch, passInputs, bindings);
    }

    /** Core binder separated from the live-target adapter so its exact pass semantics are testable. */
    static IrisMetalOptimizationPlan.AttachmentLifetimeReceipt compile(
            final IrisMetalOptimizationPlan plan,
            final int chainGeneration,
            final List<RasterPassInput> passInputs,
            final List<AllocationBinding> bindings
    ) {
        return compile(plan, chainGeneration, 0L, passInputs, bindings);
    }

    static IrisMetalOptimizationPlan.AttachmentLifetimeReceipt compile(
            final IrisMetalOptimizationPlan plan,
            final int chainGeneration,
            final long targetEpoch,
            final List<RasterPassInput> passInputs,
            final List<AllocationBinding> bindings
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(passInputs, "passInputs");
        Objects.requireNonNull(bindings, "bindings");
        String targetSignature = targetSignature(bindings);
        if (plan.chainGeneration() != chainGeneration) {
            return unresolvedReceipt(
                    plan.chainGeneration(), targetEpoch, targetSignature, "chain-generation-mismatch"
            );
        }

        Set<String> unresolvedConsumers = new LinkedHashSet<>();
        Map<String, IrisMetalOptimizationPlan.PlanPass> planPasses = new LinkedHashMap<>();
        for (IrisMetalOptimizationPlan.PlanPass pass : plan.passReceipt()) {
            if (pass.type() == PassType.RENDER) {
                planPasses.put(pass.planPassKey(), pass);
            } else {
                for (IrisMetalHazardGraph.ResourceUse use : pass.logicalUses()) {
                    unresolvedConsumers.add(pass.planPassKey() + ":" + use.resource()
                            + "/" + use.access().name());
                }
            }
        }
        Map<String, AllocationBinding> bindingByKey = new LinkedHashMap<>();
        for (AllocationBinding binding : bindings) {
            bindingByKey.put(bindingKey(binding.logicalTarget(), binding.physicalSide()), binding);
        }

        List<Candidate> candidates = new ArrayList<>();
        Map<String, List<Event>> events = new LinkedHashMap<>();
        Set<String> matchedPlanPasses = new LinkedHashSet<>();
        int passIndex = 0;

        for (RasterPassInput passInput : passInputs) {
                IrisMetalPostChain.PassInfo info = passInput.info();
                String planPassKey = passInput.planPassKey();
                IrisMetalOptimizationPlan.PlanPass planPass = planPasses.get(planPassKey);
                if (planPass == null) {
                    unresolvedConsumers.add(planPassKey + ":missing-plan-pass");
                    passIndex++;
                    continue;
                }
                matchedPlanPasses.add(planPassKey);
                boolean passUnresolved = collectUnresolvedConsumers(
                        planPass, info, planPassKey, unresolvedConsumers
                );
                int[] drawBuffers = info.drawBuffers();
                for (int slot = 0; slot < planPass.attachmentCandidates().size(); slot++) {
                    IrisMetalOptimizationPlan.AttachmentPolicy policy =
                            planPass.attachmentCandidates().get(slot);
                    int logicalTarget = IrisMetalPostChain.renderTargetIndex(policy.resource());
                    int drawSlot = find(drawBuffers, logicalTarget);
                    if (logicalTarget < 0 || drawSlot < 0) {
                        unresolvedConsumers.add(planPassKey + ":attachment/" + policy.resource());
                        candidates.add(unresolvedCandidate(
                                planPass, policy, slot, passIndex, "attachment-not-raster-bound"
                        ));
                        continue;
                    }
                    boolean readsAlt = info.readsFromAlt().get(logicalTarget);
                    String physicalSide = readsAlt ? "main" : "alt";
                    AllocationBinding binding = bindingByKey.get(bindingKey(logicalTarget, physicalSide));
                    if (binding == null) {
                        unresolvedConsumers.add(planPassKey + ":allocation/" + logicalTarget + "/" + physicalSide);
                        candidates.add(unresolvedCandidate(
                                planPass, policy, slot, passIndex, "allocation-unavailable"
                        ));
                        continue;
                    }
                    String allocationKey = binding.allocationKey();
                    addEvent(events, allocationKey, new Event(
                            passIndex, IrisMetalHazardGraph.Access.ATTACHMENT_WRITE
                    ));
                    candidates.add(new Candidate(
                            planPass.planPassKey(),
                            planPass.semanticPassId(),
                            drawSlot,
                            policy.resource(),
                            passIndex,
                            physicalSide,
                            policy.load(),
                            policy.store(),
                            binding,
                            passUnresolved
                    ));
                }
                collectRasterReads(info, passIndex, bindingByKey, events, unresolvedConsumers, planPassKey);
                passIndex++;
        }

        // The final pass writes an externally-owned MainTarget. It is part of
        // the logical plan, but never receives a guessed Metal allocation.
        for (IrisMetalOptimizationPlan.PlanPass planPass : planPasses.values()) {
            if (!matchedPlanPasses.contains(planPass.planPassKey())) {
                for (int slot = 0; slot < planPass.attachmentCandidates().size(); slot++) {
                    IrisMetalOptimizationPlan.AttachmentPolicy policy =
                            planPass.attachmentCandidates().get(slot);
                    unresolvedConsumers.add(planPass.planPassKey() + ":external-or-missing");
                    candidates.add(unresolvedCandidate(
                            planPass, policy, slot, passIndex, "external-or-missing-pass"
                    ));
                }
                passIndex++;
            }
        }

        List<IrisMetalOptimizationPlan.AttachmentLifetime> lifetimes = new ArrayList<>();
        Map<String, IrisMetalOptimizationPlan.AttachmentLifetime> lifetimeByKey = new HashMap<>();
        events.forEach((allocationKey, allocationEvents) -> {
            AllocationBinding binding = bindingForKey(bindings, allocationKey);
            if (binding == null) {
                return;
            }
            List<Event> ordered = allocationEvents.stream()
                    .sorted(Comparator.comparingInt(Event::passIndex))
                    .toList();
            int firstUse = ordered.stream().mapToInt(Event::passIndex).min().orElseThrow();
            int lastWrite = ordered.stream()
                    .filter(event -> event.access().writes())
                    .mapToInt(Event::passIndex)
                    .max().orElse(-1);
            Event next = ordered.stream()
                    .filter(event -> lastWrite < 0 || event.passIndex() > lastWrite)
                    .findFirst().orElse(null);
            IrisMetalOptimizationPlan.AttachmentLifetime lifetime =
                    new IrisMetalOptimizationPlan.AttachmentLifetime(
                            allocationKey,
                            binding.identity().allocationId(),
                            binding.identity().generation(),
                            0,
                            firstUse,
                            lastWrite,
                            next == null ? -1 : next.passIndex(),
                            next == null ? "NONE" : next.access().name()
                    );
            lifetimes.add(lifetime);
            lifetimeByKey.put(allocationKey, lifetime);
        });
        lifetimes.sort(Comparator.comparing(IrisMetalOptimizationPlan.AttachmentLifetime::allocationKey));

        List<IrisMetalOptimizationPlan.ResolvedAttachment> attachments = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.binding() == null || candidate.unresolved()) {
                attachments.add(new IrisMetalOptimizationPlan.ResolvedAttachment(
                        candidate.planPassKey(),
                        candidate.semanticPassId(),
                        candidate.slot(),
                        candidate.logicalResource(),
                        0L,
                        0L,
                        0,
                        UNRESOLVED_KEY,
                        candidate.load(),
                        candidate.store(),
                        candidate.passIndex(),
                        IrisMetalOptimizationPlan.AttachmentResolution.UNRESOLVED_CONSERVATIVE,
                        IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT,
                        UNRESOLVED_KEY,
                        null
                ));
                continue;
            }
            String allocationKey = candidate.binding().allocationKey();
            attachments.add(new IrisMetalOptimizationPlan.ResolvedAttachment(
                    candidate.planPassKey(),
                    candidate.semanticPassId(),
                    candidate.slot(),
                    candidate.logicalResource(),
                    candidate.binding().identity().allocationId(),
                    candidate.binding().identity().generation(),
                    0,
                    candidate.physicalSide(),
                    candidate.load(),
                    candidate.store(),
                    candidate.passIndex(),
                    IrisMetalOptimizationPlan.AttachmentResolution.RESOLVED_RASTER,
                    IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT,
                    allocationKey,
                    Objects.requireNonNull(lifetimeByKey.get(allocationKey), allocationKey)
            ));
        }
        String status = unresolvedConsumers.isEmpty()
                ? "RESOLVED_CONSERVATIVE"
                : "UNRESOLVED_CONSERVATIVE";
        return new IrisMetalOptimizationPlan.AttachmentLifetimeReceipt(
                plan.chainGeneration(),
                targetEpoch,
                targetSignature,
                status,
                attachments,
                lifetimes,
                List.copyOf(unresolvedConsumers)
        );
    }

    static String targetSignature(final IrisMetalRenderTargets targets) {
        List<AllocationBinding> bindings = new ArrayList<>();
        for (int logical = 0; logical < targets.colorTargets().targetCount(); logical++) {
            bindings.add(new AllocationBinding(
                    logical, "main", targets.colorTargets().mainTexture(logical).allocationIdentity()
            ));
            bindings.add(new AllocationBinding(
                    logical, "alt", targets.colorTargets().altTexture(logical).allocationIdentity()
            ));
        }
        return targetSignature(bindings);
    }

    static String targetSignature(final List<AllocationBinding> bindings) {
        StringBuilder signature = new StringBuilder("color/");
        bindings.stream()
                .sorted(Comparator.comparingInt(AllocationBinding::logicalTarget)
                        .thenComparing(AllocationBinding::physicalSide))
                .forEach(binding -> signature.append(binding.logicalTarget())
                        .append(':').append(binding.physicalSide())
                        .append('@').append(binding.identity().allocationId())
                        .append('/').append(binding.identity().generation()).append(';'));
        return signature.toString();
    }

    static IrisMetalOptimizationPlan.AttachmentLifetimeReceipt unresolvedReceipt(
            final int chainGeneration,
            final long targetEpoch,
            final String targetSignature,
            final String reason
    ) {
        return new IrisMetalOptimizationPlan.AttachmentLifetimeReceipt(
                chainGeneration,
                targetEpoch,
                targetSignature,
                "UNRESOLVED_CONSERVATIVE",
                List.of(),
                List.of(),
                List.of(reason)
        );
    }

    static IrisMetalOptimizationPlan.AttachmentLifetimeReceipt staleReceipt(
            final IrisMetalOptimizationPlan.AttachmentLifetimeReceipt previous,
            final long targetEpoch,
            final String targetSignature
    ) {
        return new IrisMetalOptimizationPlan.AttachmentLifetimeReceipt(
                previous.chainGeneration(),
                targetEpoch,
                targetSignature,
                "STALE_UNRESOLVED",
                List.of(),
                List.of(),
                List.of("target-reallocated")
        );
    }

    private static boolean collectUnresolvedConsumers(
            final IrisMetalOptimizationPlan.PlanPass planPass,
            final IrisMetalPostChain.PassInfo info,
            final String planPassKey,
            final Set<String> unresolvedConsumers
    ) {
        boolean unresolved = false;
        for (IrisMetalHazardGraph.ResourceUse use : planPass.logicalUses()) {
            int target = IrisMetalPostChain.renderTargetIndex(use.resource());
            boolean supportedRasterUse = target >= 0
                    && (use.access() == IrisMetalHazardGraph.Access.SAMPLED_READ
                    || use.access() == IrisMetalHazardGraph.Access.ATTACHMENT_WRITE);
            if (!supportedRasterUse) {
                unresolvedConsumers.add(planPassKey + ":" + use.resource() + "/" + use.access().name());
                unresolved = true;
            }
        }
        for (String sampler : info.declaredSamplers()) {
            int target = IrisMetalPostChain.renderTargetIndex(sampler);
            if (target < 0) {
                unresolvedConsumers.add(planPassKey + ":sampler/" + sampler);
                unresolved = true;
            }
        }
        return unresolved;
    }

    private static void collectRasterReads(
            final IrisMetalPostChain.PassInfo info,
            final int passIndex,
            final Map<String, AllocationBinding> bindingByKey,
            final Map<String, List<Event>> events,
            final Set<String> unresolvedConsumers,
            final String planPassKey
    ) {
        for (String sampler : info.declaredSamplers()) {
            int target = IrisMetalPostChain.renderTargetIndex(sampler);
            if (target < 0) {
                continue;
            }
            boolean readsAlt = info.readsFromAlt().get(target);
            AllocationBinding binding = bindingByKey.get(bindingKey(target, readsAlt ? "alt" : "main"));
            if (binding == null) {
                unresolvedConsumers.add(planPassKey + ":read-allocation/" + target);
                continue;
            }
            addEvent(events, binding.allocationKey(), new Event(
                    passIndex, IrisMetalHazardGraph.Access.SAMPLED_READ
            ));
        }
    }

    private static Candidate unresolvedCandidate(
            final IrisMetalOptimizationPlan.PlanPass planPass,
            final IrisMetalOptimizationPlan.AttachmentPolicy policy,
            final int slot,
            final int passIndex,
            final String ignoredReason
    ) {
        return new Candidate(
                planPass.planPassKey(),
                planPass.semanticPassId(),
                slot,
                policy.resource(),
                passIndex,
                UNRESOLVED_KEY,
                policy.load(),
                policy.store(),
                null,
                true
        );
    }

    private static int find(final int[] values, final int wanted) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == wanted) {
                return index;
            }
        }
        return -1;
    }

    private static String bindingKey(final int logicalTarget, final String physicalSide) {
        return logicalTarget + "/" + physicalSide;
    }

    private static void addEvent(
            final Map<String, List<Event>> events,
            final String allocationKey,
            final Event event
    ) {
        events.computeIfAbsent(allocationKey, ignored -> new ArrayList<>()).add(event);
    }

    private static AllocationBinding bindingForKey(
            final List<AllocationBinding> bindings,
            final String allocationKey
    ) {
        for (AllocationBinding binding : bindings) {
            if (binding.allocationKey().equals(allocationKey)) {
                return binding;
            }
        }
        return null;
    }
}
