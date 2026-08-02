package com.metallum.client.metal.render;

import com.metallum.Metallum;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime scope for grouping independent non-concurrent Iris compute dispatches.
 *
 * <p>The post-chain still creates one {@link MetalComputePass} per logical
 * dispatch. While an approved group is active, the command encoder mixin keeps
 * the underlying native compute encoder alive between those logical passes and
 * closes it after the last pass. Logical contract traces remain separate.</p>
 */
public final class IrisMetalComputeGroupingRuntime {
    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private IrisMetalComputeGroupingRuntime() {
    }

    public static boolean begin(final List<?> computes, final boolean concurrentCompute) {
        boolean enabled = IrisMetalOptimizationPlan.ENABLE_COMPUTE_GROUPING
                || IrisMetalAdvancedOptimizationConfig.COMPUTE_GROUPING;
        if (concurrentCompute || !enabled || computes == null || computes.size() < 2) {
            STATE.remove();
            return false;
        }
        try {
            if (!independent(computes)) {
                STATE.remove();
                return false;
            }
            STATE.set(new State(computes.size()));
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            STATE.remove();
            Metallum.LOGGER.warn(
                    "[metallum-iris-opt] compute grouping analysis failed; retaining encoder boundaries",
                    failure
            );
            return false;
        }
    }

    /** Returns the current encoder when a later logical pass in the group opens. */
    public static boolean mayReuseEncoder() {
        State state = STATE.get();
        return state != null && state.closedPasses > 0 && state.closedPasses < state.passCount;
    }

    /**
     * Called when a logical compute pass closes. Returns true when native
     * endEncoding must be deferred because another approved dispatch follows.
     */
    public static boolean deferClose() {
        State state = STATE.get();
        if (state == null) return false;
        state.closedPasses++;
        if (state.closedPasses < state.passCount) {
            return true;
        }
        STATE.remove();
        return false;
    }

    public static void abort() {
        STATE.remove();
    }

    private static boolean independent(final List<?> computes) throws ReflectiveOperationException {
        Set<String> priorReads = new LinkedHashSet<>();
        Set<String> priorWrites = new LinkedHashSet<>();
        for (Object compute : computes) {
            AccessSet access = access(compute);
            if (intersects(access.reads, priorWrites)
                    || intersects(access.writes, priorReads)
                    || intersects(access.writes, priorWrites)) {
                return false;
            }
            priorReads.addAll(access.reads);
            priorWrites.addAll(access.writes);
        }
        return true;
    }

    private static AccessSet access(final Object compute) throws ReflectiveOperationException {
        Object reflection = field(compute, "reflection");
        Object value = invoke(reflection, "resources");
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        if (value instanceof Collection<?> resources) {
            for (Object resource : resources) {
                String name = stableName(String.valueOf(invoke(resource, "name")));
                String kind = String.valueOf(invoke(resource, "kind"));
                if (kind.contains("STORAGE")) writes.add(name);
                else reads.add(name);
            }
        }
        return new AccessSet(Set.copyOf(reads), Set.copyOf(writes));
    }

    private static boolean intersects(final Set<String> first, final Set<String> second) {
        Set<String> smaller = first.size() <= second.size() ? first : second;
        Set<String> larger = first.size() <= second.size() ? second : first;
        for (String value : smaller) if (larger.contains(value)) return true;
        return false;
    }

    private static String stableName(final String name) {
        if (name.matches("colorimg\\d+")) return "colortex" + name.substring("colorimg".length());
        return switch (name) {
            case "gcolor" -> "colortex0";
            case "gdepth" -> "colortex1";
            case "gnormal" -> "colortex2";
            case "composite" -> "colortex3";
            case "gaux1" -> "colortex4";
            case "gaux2" -> "colortex5";
            case "gaux3" -> "colortex6";
            case "gaux4" -> "colortex7";
            default -> name;
        };
    }

    private static Object field(final Object target, final String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke(final Object target, final String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method.invoke(target);
                }
            }
            type = type.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    private record AccessSet(Set<String> reads, Set<String> writes) {
    }

    private static final class State {
        private final int passCount;
        private int closedPasses;

        private State(final int passCount) {
            this.passCount = passCount;
        }
    }
}
