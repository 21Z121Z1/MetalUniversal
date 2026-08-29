package com.metallum.client.metal.render;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generation-scoped execution authority for an Iris heap-alias recipe.
 *
 * <p>The first target allocation of a shader-pack generation remains the
 * ordinary device-backed path because no physical lifetime receipt exists yet.
 * Once the live allocation set has been compiled, only stable semantic/physical
 * keys survive here so a later resize can re-create resources without carrying
 * native allocation identities across generations.</p>
 */
final class IrisMetalHeapAliasRuntime {
    static final boolean ENABLED = Boolean.getBoolean("metallum.iris.experimental.heapAliasing");

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
        if (!ENABLED || receipt == null) {
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
        return ENABLED ? ACTIVE.get() : null;
    }

    static @Nullable Published forGeneration(final int chainGeneration) {
        Published current = current();
        return current != null && current.chainGeneration() == chainGeneration ? current : null;
    }

    static void clear() {
        ACTIVE.set(null);
    }
}
