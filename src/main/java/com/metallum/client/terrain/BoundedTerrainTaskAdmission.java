package com.metallum.client.terrain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, fail-open admission state for vanilla 26.3 terrain work.
 *
 * <p>This class deliberately has no Minecraft types. It does not choose which section is correct
 * or alter vanilla's initial/recompile ordering. It only decides whether a newly scheduled task is
 * admitted to the existing vanilla queue, coalesced into a bounded deferred set, or causes the
 * experiment to fail open to vanilla behavior. Deferred work is drained in first-waiting order;
 * replacing a superseded task preserves that slot's original age.</p>
 */
public final class BoundedTerrainTaskAdmission<T> {
    public static final String ENABLE_PROPERTY = "metallum.terrain.vanillaAdmission";
    public static final String QUEUE_CAPACITY_PROPERTY = "metallum.terrain.vanillaQueueCapacity";
    public static final String DEFERRED_CAPACITY_PROPERTY = "metallum.terrain.vanillaDeferredCapacity";
    public static final int MAX_CAPACITY = 65_536;

    public enum Action {
        BASELINE,
        ADMIT,
        DEFER,
        REPLACE_DEFERRED,
        DISCARD_TERMINAL,
        FAIL_OPEN
    }

    public interface TaskOps<T> {
        Object ownerIdentity(T task);

        Object kindIdentity(T task);

        boolean isTerminal(T task);

        void cancel(T task);
    }

    public record Config(boolean requested, int queueCapacity, int deferredCapacity) {
        public Config {
            if (queueCapacity < 0 || queueCapacity > MAX_CAPACITY) {
                throw new IllegalArgumentException("queueCapacity out of range: " + queueCapacity);
            }
            if (deferredCapacity < 0 || deferredCapacity > MAX_CAPACITY) {
                throw new IllegalArgumentException("deferredCapacity out of range: " + deferredCapacity);
            }
        }

        public boolean active() {
            return requested && queueCapacity > 0 && deferredCapacity > 0;
        }

        public static Config fromSystemProperties() {
            return new Config(
                    Boolean.getBoolean(ENABLE_PROPERTY),
                    boundedProperty(QUEUE_CAPACITY_PROPERTY),
                    boundedProperty(DEFERRED_CAPACITY_PROPERTY)
            );
        }

        private static int boundedProperty(final String name) {
            Integer value = Integer.getInteger(name);
            return value == null || value < 1 || value > MAX_CAPACITY ? 0 : value;
        }
    }

    public record OfferResult<T>(Action action, List<T> failOpenTasks) {
        public OfferResult {
            Objects.requireNonNull(action, "action");
            failOpenTasks = List.copyOf(failOpenTasks);
        }

        static <T> OfferResult<T> of(final Action action) {
            return new OfferResult<>(action, List.of());
        }
    }

    public record DrainResult<T>(List<T> tasks, long oldestWaitNanos) {
        public DrainResult {
            tasks = List.copyOf(tasks);
            if (oldestWaitNanos < 0L) {
                throw new IllegalArgumentException("oldestWaitNanos must be non-negative");
            }
        }
    }

    public record Snapshot(
            boolean requested,
            boolean active,
            boolean failOpen,
            int queueCapacity,
            int deferredCapacity,
            int deferredTasks,
            long admittedTasks,
            long deferredTasksTotal,
            long replacedDeferredTasks,
            long discardedTerminalTasks,
            long drainedTasks,
            long failOpenCount,
            long compactedVanillaTasks,
            int maxDeferredDepth,
            long maxDeferredWaitNanos
    ) {
    }

    private final Config config;
    private final TaskOps<T> taskOps;
    private final Map<IdentitySlot, Pending<T>> deferred = new LinkedHashMap<>();

    private boolean failOpen;
    private long admittedTasks;
    private long deferredTasksTotal;
    private long replacedDeferredTasks;
    private long discardedTerminalTasks;
    private long drainedTasks;
    private long failOpenCount;
    private long compactedVanillaTasks;
    private int maxDeferredDepth;
    private long maxDeferredWaitNanos;

    public BoundedTerrainTaskAdmission(final Config config, final TaskOps<T> taskOps) {
        this.config = Objects.requireNonNull(config, "config");
        this.taskOps = Objects.requireNonNull(taskOps, "taskOps");
    }

    public boolean active() {
        return config.active() && !failOpen;
    }

    public int queueCapacity() {
        return config.queueCapacity();
    }

    public int deferredSize() {
        return deferred.size();
    }

    public long oldestDeferredAgeNanos(final long nowNanos) {
        if (nowNanos < 0L) {
            throw new IllegalArgumentException("nowNanos must be non-negative");
        }
        if (deferred.isEmpty()) {
            return 0L;
        }
        Pending<T> oldest = deferred.values().iterator().next();
        return Math.max(0L, nowNanos - oldest.firstDeferredNanos());
    }

    /**
     * Decides where a new vanilla task belongs. The caller must pass the number of live entries in
     * the original queue after safely compacting terminal entries when it is at capacity.
     */
    public OfferResult<T> offer(final T task, final int liveQueuedTasks, final long nowNanos) {
        Objects.requireNonNull(task, "task");
        if (liveQueuedTasks < 0) {
            throw new IllegalArgumentException("liveQueuedTasks must be non-negative");
        }
        if (nowNanos < 0L) {
            throw new IllegalArgumentException("nowNanos must be non-negative");
        }
        if (!config.active() || failOpen) {
            return OfferResult.of(Action.BASELINE);
        }
        if (taskOps.isTerminal(task)) {
            discardedTerminalTasks = saturatedIncrement(discardedTerminalTasks);
            return OfferResult.of(Action.DISCARD_TERMINAL);
        }

        IdentitySlot slot = slot(task);
        Pending<T> previous = deferred.get(slot);
        if (previous != null) {
            // A latest-wins replacement is safe only when vanilla has already made the old task
            // terminal. Otherwise there are two live semantic tasks in the same slot and this
            // experiment lacks enough identity to coalesce them safely.
            if (!taskOps.isTerminal(previous.task())) {
                return failOpen();
            }
            taskOps.cancel(previous.task());
            deferred.put(slot, new Pending<>(task, previous.firstDeferredNanos()));
            replacedDeferredTasks = saturatedIncrement(replacedDeferredTasks);
            return OfferResult.of(Action.REPLACE_DEFERRED);
        }

        if (liveQueuedTasks < config.queueCapacity()) {
            admittedTasks = saturatedIncrement(admittedTasks);
            return OfferResult.of(Action.ADMIT);
        }

        if (deferred.size() < config.deferredCapacity()) {
            deferred.put(slot, new Pending<>(task, nowNanos));
            deferredTasksTotal = saturatedIncrement(deferredTasksTotal);
            maxDeferredDepth = Math.max(maxDeferredDepth, deferred.size());
            return OfferResult.of(Action.DEFER);
        }

        return failOpen();
    }

    /**
     * Moves the oldest still-live deferred tasks back to vanilla when queue capacity is available.
     * Vanilla remains responsible for distance ordering and its MAX_RECOMPILE_QUOTA after this.
     */
    public DrainResult<T> drain(final int availableQueueSlots, final long nowNanos) {
        if (availableQueueSlots < 0) {
            throw new IllegalArgumentException("availableQueueSlots must be non-negative");
        }
        if (nowNanos < 0L) {
            throw new IllegalArgumentException("nowNanos must be non-negative");
        }
        if (!active() || availableQueueSlots == 0 || deferred.isEmpty()) {
            return new DrainResult<>(List.of(), 0L);
        }

        List<T> ready = new ArrayList<>(Math.min(availableQueueSlots, deferred.size()));
        long oldestWait = 0L;
        var iterator = deferred.entrySet().iterator();
        while (iterator.hasNext() && ready.size() < availableQueueSlots) {
            Pending<T> pending = iterator.next().getValue();
            iterator.remove();
            if (taskOps.isTerminal(pending.task())) {
                discardedTerminalTasks = saturatedIncrement(discardedTerminalTasks);
                continue;
            }
            ready.add(pending.task());
            long wait = Math.max(0L, nowNanos - pending.firstDeferredNanos());
            oldestWait = Math.max(oldestWait, wait);
            maxDeferredWaitNanos = Math.max(maxDeferredWaitNanos, wait);
            drainedTasks = saturatedIncrement(drainedTasks);
        }
        return new DrainResult<>(ready, oldestWait);
    }

    /** Records entries that vanilla would otherwise discard lazily during poll(). */
    public void recordCompactedVanillaTasks(final int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        compactedVanillaTasks = saturatedAdd(compactedVanillaTasks, count);
    }

    /**
     * Cancels only work owned by this deferred layer and re-arms the experimental path for a new
     * vanilla queue epoch. The original queue is cleared by vanilla itself.
     */
    public void clearDeferredAndReset() {
        for (Pending<T> pending : deferred.values()) {
            taskOps.cancel(pending.task());
        }
        deferred.clear();
        failOpen = false;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                config.requested(),
                config.active(),
                failOpen,
                config.queueCapacity(),
                config.deferredCapacity(),
                deferred.size(),
                admittedTasks,
                deferredTasksTotal,
                replacedDeferredTasks,
                discardedTerminalTasks,
                drainedTasks,
                failOpenCount,
                compactedVanillaTasks,
                maxDeferredDepth,
                maxDeferredWaitNanos
        );
    }

    private OfferResult<T> failOpen() {
        failOpen = true;
        failOpenCount = saturatedIncrement(failOpenCount);
        List<T> tasks = deferred.values().stream()
                .map(Pending::task)
                .filter(task -> !taskOps.isTerminal(task))
                .toList();
        deferred.clear();
        return new OfferResult<>(Action.FAIL_OPEN, tasks);
    }

    private IdentitySlot slot(final T task) {
        Object owner = Objects.requireNonNull(taskOps.ownerIdentity(task), "ownerIdentity");
        Object kind = Objects.requireNonNull(taskOps.kindIdentity(task), "kindIdentity");
        return new IdentitySlot(owner, kind);
    }

    private static long saturatedIncrement(final long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long saturatedAdd(final long left, final long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private record Pending<T>(T task, long firstDeferredNanos) {
    }

    private static final class IdentitySlot {
        private final Object owner;
        private final Object kind;
        private final int hash;

        private IdentitySlot(final Object owner, final Object kind) {
            this.owner = owner;
            this.kind = kind;
            this.hash = 31 * System.identityHashCode(owner) + System.identityHashCode(kind);
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof IdentitySlot slot && owner == slot.owner && kind == slot.kind;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
