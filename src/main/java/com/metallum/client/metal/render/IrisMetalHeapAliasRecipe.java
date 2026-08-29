package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles a fully-resolved physical attachment receipt into a stable heap
 * alias recipe that can be consumed by the next allocation generation.
 *
 * <p>The receipt's allocation ids are intentionally used only to join the
 * compiler facts for the current generation. They are never published as the
 * cross-generation identity. The recipe instead names each physical texture
 * by the stable Iris logical resource + physical ping-pong side + mip level.
 * This lets a resize/reallocation generation recreate the same lifetime slots
 * with new Metal allocation identities without accidentally reusing a stale
 * native pointer.</p>
 *
 * <p>Each member owns a closed [firstUse,lastUse] interval. Two members may
 * share one automatic MTLHeap allocation slot only when those intervals are
 * strictly disjoint. The emitted handoff edge is the only legal point at which
 * the executor may mark the former heap resource aliasable and create the next
 * resource. Native execution still has to prove GPU ordering at that edge.</p>
 */
final class IrisMetalHeapAliasRecipe {
    private IrisMetalHeapAliasRecipe() {
    }

    record Member(
            String resourceKey,
            String sourceAllocationKey,
            int firstUse,
            int lastUse
    ) {
        Member {
            Objects.requireNonNull(resourceKey, "resourceKey");
            Objects.requireNonNull(sourceAllocationKey, "sourceAllocationKey");
            if (resourceKey.isBlank() || sourceAllocationKey.isBlank()) {
                throw new IllegalArgumentException("Heap alias member keys must not be blank");
            }
            if (firstUse < 0 || lastUse < firstUse) {
                throw new IllegalArgumentException("Invalid heap alias lifetime");
            }
        }
    }

    record Handoff(
            String fromResourceKey,
            String toResourceKey,
            int afterPass,
            int beforePass
    ) {
        Handoff {
            Objects.requireNonNull(fromResourceKey, "fromResourceKey");
            Objects.requireNonNull(toResourceKey, "toResourceKey");
            if (afterPass < 0 || beforePass <= afterPass) {
                throw new IllegalArgumentException("Heap alias handoff must cross a strict lifetime gap");
            }
        }
    }

    record AliasSlot(int slotIndex, List<Member> members, List<Handoff> handoffs) {
        AliasSlot {
            if (slotIndex < 0) {
                throw new IllegalArgumentException("Alias slot index must be non-negative");
            }
            members = List.copyOf(members);
            handoffs = List.copyOf(handoffs);
            if (members.size() < 2 || handoffs.size() != members.size() - 1) {
                throw new IllegalArgumentException("Alias slots require at least two ordered members");
            }
            for (int index = 1; index < members.size(); index++) {
                Member previous = members.get(index - 1);
                Member current = members.get(index);
                if (previous.lastUse() >= current.firstUse()) {
                    throw new IllegalArgumentException("Overlapping members cannot share an alias slot");
                }
            }
        }
    }

    record Recipe(
            int chainGeneration,
            String status,
            List<AliasSlot> aliasSlots,
            List<Member> dedicatedMembers,
            List<String> rejectedReasons
    ) {
        Recipe {
            if (chainGeneration < 0) {
                throw new IllegalArgumentException("Chain generation must be non-negative");
            }
            Objects.requireNonNull(status, "status");
            aliasSlots = List.copyOf(aliasSlots);
            dedicatedMembers = List.copyOf(dedicatedMembers);
            rejectedReasons = List.copyOf(rejectedReasons);
        }

        boolean executable() {
            return "RESOLVED_ALIAS_RECIPE".equals(status) && rejectedReasons.isEmpty();
        }

        int aliasedResourceCount() {
            return aliasSlots.stream().mapToInt(slot -> slot.members().size()).sum();
        }
    }

    private static final class MutableSlot {
        final int index;
        final List<Member> members = new ArrayList<>();
        int lastUse = -1;

        MutableSlot(final int index) {
            this.index = index;
        }
    }

    static Recipe compile(final IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (!"RESOLVED_CONSERVATIVE".equals(receipt.status())
                || !receipt.unresolvedConsumers().isEmpty()) {
            return rejected(receipt, "attachment-receipt-not-fully-resolved");
        }

        Map<String, String> stableKeyByAllocation = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        for (IrisMetalOptimizationPlan.ResolvedAttachment attachment : receipt.attachments()) {
            if (attachment.resolution()
                    != IrisMetalOptimizationPlan.AttachmentResolution.RESOLVED_RASTER) {
                continue;
            }
            String stableKey = stableResourceKey(attachment);
            String previous = stableKeyByAllocation.putIfAbsent(attachment.allocationKey(), stableKey);
            if (previous != null && !previous.equals(stableKey)) {
                reasons.add("allocation-maps-to-multiple-physical-sides:" + attachment.allocationKey());
            }
        }
        if (!reasons.isEmpty()) {
            return new Recipe(receipt.chainGeneration(), "REJECTED", List.of(), List.of(), reasons);
        }

        Map<String, IrisMetalOptimizationPlan.AttachmentLifetime> lifetimeByAllocation = new HashMap<>();
        for (IrisMetalOptimizationPlan.AttachmentLifetime lifetime : receipt.lifetimes()) {
            IrisMetalOptimizationPlan.AttachmentLifetime previous =
                    lifetimeByAllocation.put(lifetime.allocationKey(), lifetime);
            if (previous != null) {
                reasons.add("duplicate-lifetime:" + lifetime.allocationKey());
            }
        }

        List<Member> members = new ArrayList<>();
        Map<String, String> allocationByStableKey = new HashMap<>();
        for (Map.Entry<String, String> entry : stableKeyByAllocation.entrySet()) {
            IrisMetalOptimizationPlan.AttachmentLifetime lifetime = lifetimeByAllocation.get(entry.getKey());
            if (lifetime == null) {
                reasons.add("missing-lifetime:" + entry.getKey());
                continue;
            }
            String previousAllocation = allocationByStableKey.putIfAbsent(entry.getValue(), entry.getKey());
            if (previousAllocation != null && !previousAllocation.equals(entry.getKey())) {
                reasons.add("physical-side-maps-to-multiple-allocations:" + entry.getValue());
                continue;
            }
            members.add(new Member(
                    entry.getValue(), entry.getKey(), lifetime.firstUse(), lifetime.lastUse()
            ));
        }
        if (!reasons.isEmpty()) {
            return new Recipe(receipt.chainGeneration(), "REJECTED", List.of(), List.of(), reasons);
        }

        members.sort(Comparator.comparingInt(Member::firstUse)
                .thenComparingInt(Member::lastUse)
                .thenComparing(Member::resourceKey));

        List<MutableSlot> slots = new ArrayList<>();
        for (Member member : members) {
            MutableSlot selected = null;
            for (MutableSlot slot : slots) {
                if (slot.lastUse < member.firstUse()) {
                    selected = slot;
                    break;
                }
            }
            if (selected == null) {
                selected = new MutableSlot(slots.size());
                slots.add(selected);
            }
            selected.members.add(member);
            selected.lastUse = member.lastUse();
        }

        List<AliasSlot> aliasSlots = new ArrayList<>();
        List<Member> dedicated = new ArrayList<>();
        for (MutableSlot slot : slots) {
            if (slot.members.size() < 2) {
                dedicated.add(slot.members.getFirst());
                continue;
            }
            List<Handoff> handoffs = new ArrayList<>();
            for (int index = 1; index < slot.members.size(); index++) {
                Member previous = slot.members.get(index - 1);
                Member current = slot.members.get(index);
                handoffs.add(new Handoff(
                        previous.resourceKey(), current.resourceKey(),
                        previous.lastUse(), current.firstUse()
                ));
            }
            aliasSlots.add(new AliasSlot(slot.index, slot.members, handoffs));
        }
        dedicated.sort(Comparator.comparing(Member::resourceKey));
        return new Recipe(
                receipt.chainGeneration(), "RESOLVED_ALIAS_RECIPE",
                aliasSlots, dedicated, List.of()
        );
    }

    private static Recipe rejected(
            final IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt,
            final String reason
    ) {
        return new Recipe(receipt.chainGeneration(), "REJECTED", List.of(), List.of(), List.of(reason));
    }

    private static String stableResourceKey(
            final IrisMetalOptimizationPlan.ResolvedAttachment attachment
    ) {
        return attachment.logicalResource()
                + "/" + attachment.physicalSide()
                + "/mip/" + attachment.mipLevel();
    }
}
