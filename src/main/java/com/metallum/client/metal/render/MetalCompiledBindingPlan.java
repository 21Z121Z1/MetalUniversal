package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Dense immutable resource layout compiled once with a render pipeline.
 *
 * <p>The compiler accepts the pipeline's internal resource-binding records as
 * {@code Object}s so this public hot-path abstraction does not expose Mojang or
 * package-private implementation types. Reflection is confined to pipeline
 * construction; draw-time lookup is token id to dense array slot.</p>
 */
public final class MetalCompiledBindingPlan {
    private static final ClassValue<BindingAccessors> ACCESSORS = new ClassValue<>() {
        @Override
        protected BindingAccessors computeValue(final Class<?> type) {
            return BindingAccessors.create(type);
        }
    };

    private final MetalBindingToken[] tokens;
    private final int[] physicalBindingIndices;
    private final int[] stageMasks;
    private final String[] resourceKinds;
    private final Int2IntOpenHashMap slotByTokenId;

    private MetalCompiledBindingPlan(
            final MetalBindingToken[] tokens,
            final int[] physicalBindingIndices,
            final int[] stageMasks,
            final String[] resourceKinds,
            final Int2IntOpenHashMap slotByTokenId
    ) {
        this.tokens = tokens;
        this.physicalBindingIndices = physicalBindingIndices;
        this.stageMasks = stageMasks;
        this.resourceKinds = resourceKinds;
        this.slotByTokenId = slotByTokenId;
    }

    public static MetalCompiledBindingPlan compile(final List<?> rawBindings) {
        Objects.requireNonNull(rawBindings, "rawBindings");
        int count = rawBindings.size();
        MetalBindingToken[] tokens = new MetalBindingToken[count];
        int[] physicalBindingIndices = new int[count];
        int[] stageMasks = new int[count];
        String[] resourceKinds = new String[count];
        Int2IntOpenHashMap slotByTokenId = new Int2IntOpenHashMap(Math.max(2, count));
        slotByTokenId.defaultReturnValue(-1);

        for (int slot = 0; slot < count; slot++) {
            Object rawBinding = Objects.requireNonNull(rawBindings.get(slot), "raw binding " + slot);
            BindingAccessors accessors = ACCESSORS.get(rawBinding.getClass());
            String name = accessors.name(rawBinding);
            MetalBindingToken token = MetalBindingTokenRegistry.resolve(name);
            int previous = slotByTokenId.putIfAbsent(token.id(), slot);
            if (previous >= 0) {
                throw new IllegalStateException(
                        "Metal pipeline binding plan contains duplicate resource token '" + name
                                + "' at slots " + previous + " and " + slot
                );
            }
            tokens[slot] = token;
            physicalBindingIndices[slot] = accessors.bindingIndex(rawBinding);
            stageMasks[slot] = accessors.stageMask(rawBinding);
            resourceKinds[slot] = accessors.kindName(rawBinding);
        }

        return new MetalCompiledBindingPlan(
                tokens,
                physicalBindingIndices,
                stageMasks,
                resourceKinds,
                slotByTokenId
        );
    }

    public int bindingCount() {
        return this.tokens.length;
    }

    public int slotFor(final MetalBindingToken token) {
        return this.slotByTokenId.get(token.id());
    }

    public MetalBindingToken token(final int slot) {
        return this.tokens[checkedSlot(slot)];
    }

    public int physicalBindingIndex(final int slot) {
        return this.physicalBindingIndices[checkedSlot(slot)];
    }

    public int stageMask(final int slot) {
        return this.stageMasks[checkedSlot(slot)];
    }

    public String resourceKind(final int slot) {
        return this.resourceKinds[checkedSlot(slot)];
    }

    private int checkedSlot(final int slot) {
        if (slot < 0 || slot >= this.tokens.length) {
            throw new IndexOutOfBoundsException("Metal binding plan slot " + slot + " of " + this.tokens.length);
        }
        return slot;
    }

    private record BindingAccessors(Method name, Method bindingIndex, Method stageMask, Method kind) {
        private static BindingAccessors create(final Class<?> type) {
            try {
                Method name = type.getDeclaredMethod("name");
                Method bindingIndex = type.getDeclaredMethod("bindingIndex");
                Method stageMask = type.getDeclaredMethod("stageMask");
                Method kind = type.getDeclaredMethod("kind");
                name.setAccessible(true);
                bindingIndex.setAccessible(true);
                stageMask.setAccessible(true);
                kind.setAccessible(true);
                return new BindingAccessors(name, bindingIndex, stageMask, kind);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(
                        "Unsupported Metal pipeline resource binding record " + type.getName(),
                        exception
                );
            }
        }

        private String name(final Object binding) {
            return (String) invoke(this.name, binding);
        }

        private int bindingIndex(final Object binding) {
            return (int) invoke(this.bindingIndex, binding);
        }

        private int stageMask(final Object binding) {
            return (int) invoke(this.stageMask, binding);
        }

        private String kindName(final Object binding) {
            Object value = invoke(this.kind, binding);
            return value instanceof Enum<?> enumeration
                    ? enumeration.name()
                    : String.valueOf(value);
        }

        private static Object invoke(final Method method, final Object target) {
            try {
                return method.invoke(target);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(
                        "Failed to compile Metal binding plan through " + method,
                        exception
                );
            }
        }
    }
}
