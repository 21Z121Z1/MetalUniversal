package com.metallum.client.metal.render;

import com.metallum.Metallum;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Planner-side authorization for the backend's existing same-attachment render
 * encoder reuse. Physical ping-pong sides are included so logical colortex
 * equality cannot accidentally hide an Iris feedback dependency.
 */
public final class IrisMetalRenderFusionRuntime {
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    private static final LongAdder admissionCandidates = new LongAdder();
    private static final LongAdder admissions = new LongAdder();
    private static final LongAdder rejections = new LongAdder();
    private static final LongAdder analysisFailures = new LongAdder();
    private static final LongAdder forcedBoundaryPasses = new LongAdder();

    private IrisMetalRenderFusionRuntime() {
    }

    public static void beginPass(final Object plannedPass) {
        State state = STATE.get();
        state.forceBoundary = true;
        if (!enabled() || plannedPass == null || IrisMetalExperimentalOptimizer.active() == null) {
            state.pending = null;
            state.forceBoundary = false;
            return;
        }
        try {
            PassAccess current = access(plannedPass);
            PassAccess previous = state.previous;
            boolean admitted = previous != null && mayFuse(previous, current);
            if (previous != null) {
                admissionCandidates.increment();
                if (admitted) {
                    admissions.increment();
                } else {
                    rejections.increment();
                }
            }
            state.forceBoundary = !admitted;
            if (state.forceBoundary) {
                forcedBoundaryPasses.increment();
            }
            state.pending = current;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            state.pending = null;
            state.forceBoundary = true;
            analysisFailures.increment();
            Metallum.LOGGER.warn(
                    "[metallum-iris-opt] render fusion analysis failed; forcing encoder boundary",
                    failure
            );
        }
    }

    public static void endPass() {
        State state = STATE.get();
        if (state.pending != null) state.previous = state.pending;
        state.pending = null;
        state.forceBoundary = false;
    }

    public static void breakChain() {
        State state = STATE.get();
        state.previous = null;
        state.pending = null;
        state.forceBoundary = false;
    }

    /** Called immediately before MetalCommandEncoder chooses encoder reuse. */
    public static boolean forceBoundary() {
        return STATE.get().forceBoundary;
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(
                admissionCandidates.sum(),
                admissions.sum(),
                rejections.sum(),
                analysisFailures.sum(),
                forcedBoundaryPasses.sum()
        );
    }

    public static synchronized void reset() {
        admissionCandidates.reset();
        admissions.reset();
        rejections.reset();
        analysisFailures.reset();
        forcedBoundaryPasses.reset();
    }

    private static boolean enabled() {
        return IrisMetalOptimizationPlan.ENABLE_PASS_FUSION;
    }

    private static boolean mayFuse(final PassAccess previous, final PassAccess current) {
        if (!previous.attachmentSignature.equals(current.attachmentSignature)) return false;
        // Sampling an attachment written by the previous logical pass requires
        // an observable encoder boundary on the current backend.
        for (String read : current.reads) {
            if (previous.writes.contains(read)) return false;
        }
        // A pass whose render target is also sampled by itself is rejected even
        // when Iris admission should already have caught the feedback loop.
        for (String write : current.writes) {
            if (current.reads.contains(write)) return false;
        }
        return true;
    }

    private static PassAccess access(final Object plannedPass) throws ReflectiveOperationException {
        Object info = field(plannedPass, "info");
        int[] drawBuffers = (int[]) invoke(info, "drawBuffers");
        BitSet readsFromAlt = (BitSet) invoke(info, "readsFromAlt");
        Object samplersValue = invoke(info, "declaredSamplers");

        List<String> signature = new ArrayList<>(drawBuffers.length);
        Set<String> writes = new LinkedHashSet<>();
        for (int target : drawBuffers) {
            String physical = physical(target, !readsFromAlt.get(target));
            signature.add(physical);
            writes.add(physical);
        }

        Set<String> reads = new LinkedHashSet<>();
        if (samplersValue instanceof Iterable<?> samplers) {
            for (Object value : samplers) {
                int target = colorTarget(String.valueOf(value));
                if (target >= 0) reads.add(physical(target, readsFromAlt.get(target)));
            }
        }
        return new PassAccess(List.copyOf(signature), Set.copyOf(reads), Set.copyOf(writes));
    }

    private static String physical(final int target, final boolean alt) {
        return "colortex" + target + (alt ? "/alt" : "/main");
    }

    private static int colorTarget(final String name) {
        if (name.matches("colortex\\d+")) return Integer.parseInt(name.substring("colortex".length()));
        return switch (name) {
            case "gcolor" -> 0;
            case "gdepth" -> 1;
            case "gnormal" -> 2;
            case "composite" -> 3;
            case "gaux1" -> 4;
            case "gaux2" -> 5;
            case "gaux3" -> 6;
            case "gaux4" -> 7;
            default -> -1;
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

    private record PassAccess(List<String> attachmentSignature, Set<String> reads, Set<String> writes) {
    }

    public record Snapshot(
            long admissionCandidates,
            long admissions,
            long rejections,
            long analysisFailures,
            long forcedBoundaryPasses
    ) {
    }

    private static final class State {
        private PassAccess previous;
        private PassAccess pending;
        private boolean forceBoundary;
    }
}
