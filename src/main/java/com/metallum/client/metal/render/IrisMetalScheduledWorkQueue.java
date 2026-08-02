package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executes a precomputed optimization plan without allowing the runtime to
 * invent new merges. The queue is intentionally callback-based so the existing
 * raster and compute encoders can adopt it incrementally.
 */
final class IrisMetalScheduledWorkQueue {
    enum Kind { RENDER, COMPUTE }

    interface EncoderScope extends AutoCloseable {
        @Override
        void close();
    }

    interface ScopeFactory {
        EncoderScope open(Kind kind, String label);
    }

    record Work(int nodeIndex, Kind kind, String name, Runnable encode) {
        Work {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(encode, "encode");
        }
    }

    private final IrisMetalOptimizationPlan plan;
    private final List<Work> work;

    IrisMetalScheduledWorkQueue(final IrisMetalOptimizationPlan plan, final List<Work> work) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.work = List.copyOf(work);
    }

    void execute(final ScopeFactory scopes) {
        Objects.requireNonNull(scopes, "scopes");
        boolean[] consumed = new boolean[work.size()];
        if (IrisMetalOptimizationPlan.ENABLE_PASS_FUSION) {
            executeGroups(plan.renderMergeGroups(), Kind.RENDER, scopes, consumed);
        }
        if (IrisMetalOptimizationPlan.ENABLE_COMPUTE_GROUPING) {
            executeGroups(plan.computeMergeGroups(), Kind.COMPUTE, scopes, consumed);
        }
        for (int index = 0; index < work.size(); index++) {
            if (consumed[index]) continue;
            Work item = work.get(index);
            try (EncoderScope ignored = scopes.open(item.kind(), item.name())) {
                item.encode().run();
            }
        }
    }

    private void executeGroups(
            final List<IrisMetalOptimizationPlan.MergeGroup> groups,
            final Kind expected,
            final ScopeFactory scopes,
            final boolean[] consumed
    ) {
        for (IrisMetalOptimizationPlan.MergeGroup group : groups) {
            List<Work> members = new ArrayList<>();
            for (int index = 0; index < work.size(); index++) {
                Work item = work.get(index);
                if (item.nodeIndex() >= group.firstNode() && item.nodeIndex() <= group.lastNode()) {
                    if (item.kind() != expected) {
                        throw new IllegalStateException("Optimization group crosses execution kind: " + item.name());
                    }
                    members.add(item);
                    consumed[index] = true;
                }
            }
            if (members.size() < 2) {
                for (int index = 0; index < work.size(); index++) {
                    if (work.get(index).nodeIndex() >= group.firstNode()
                            && work.get(index).nodeIndex() <= group.lastNode()) consumed[index] = false;
                }
                continue;
            }
            try (EncoderScope ignored = scopes.open(expected, String.join("+", group.names()))) {
                for (Work item : members) item.encode().run();
            }
        }
    }
}
