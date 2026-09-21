package com.metallum.client.metal.render;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generation-scoped execution authority for placement-heap alias recipes.
 *
 * <p>The first allocation of a shader-pack generation is intentionally kept
 * on the ordinary path: it is the source of the physical lifetime receipt.
 * A later resize can consume the receipt, but only while the chain generation
 * still matches.  Any unresolved, stale or disabled state clears the
 * authority and leaves the renderer on dedicated allocations.</p>
 */
final class IrisMetalHeapAliasRuntime {
    record Published(
            int chainGeneration,
            IrisMetalHeapAliasRecipe.Recipe recipe,
            Map<String, Integer> slotByResource
    ) {
        Published {
            Objects.requireNonNull(recipe, "recipe");
            slotByResource = Map.copyOf(slotByResource);
            if (!recipe.executable() || recipe.chainGeneration() != chainGeneration) {
                throw new IllegalArgumentException("Only executable generation-matched recipes may be published");
            }
        }

        int slotFor(final String resourceKey) {
            return slotByResource.getOrDefault(resourceKey, -1);
        }
    }

    private static final AtomicReference<Published> ACTIVE = new AtomicReference<>();

    private IrisMetalHeapAliasRuntime() {
    }

    static void publish(final IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt) {
        if (!IrisMetalOptimizationPlan.ENABLE_HEAP_ALIASING || receipt == null) {
            ACTIVE.set(null);
            return;
        }
        IrisMetalHeapAliasRecipe.Recipe recipe = IrisMetalHeapAliasRecipe.compile(receipt);
        if (!recipe.executable() || recipe.aliasSlots().isEmpty()) {
            ACTIVE.set(null);
            return;
        }
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (IrisMetalHeapAliasRecipe.AliasSlot slot : recipe.aliasSlots()) {
            for (IrisMetalHeapAliasRecipe.Member member : slot.members()) {
                Integer previous = slots.put(member.resourceKey(), slot.slotIndex());
                if (previous != null) {
                    ACTIVE.set(null);
                    return;
                }
            }
        }
        ACTIVE.set(new Published(receipt.chainGeneration(), recipe, slots));
    }

    static @Nullable Published current() {
        return IrisMetalOptimizationPlan.ENABLE_HEAP_ALIASING ? ACTIVE.get() : null;
    }

    static @Nullable Published forGeneration(final int chainGeneration) {
        Published current = current();
        return current != null && current.chainGeneration() == chainGeneration ? current : null;
    }

    static void clear() {
        ACTIVE.set(null);
    }
}
