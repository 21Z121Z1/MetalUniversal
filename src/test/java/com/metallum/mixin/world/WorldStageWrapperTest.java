package com.metallum.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises production wrapper bodies; actual Mixin application still requires a client run. */
final class WorldStageWrapperTest {
    private static void invoke(Object target, String name, Class<?>[] signature, Object... arguments) throws Throwable {
        var method = ClientLevelWorldStagesMixin.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static ClientLevelWorldStagesMixin fixture(ArrayDeque<Runnable> queue) throws Exception {
        var target = new ClientLevelWorldStagesMixin() { };
        var field = ClientLevelWorldStagesMixin.class.getDeclaredField("lightUpdateQueue");
        field.setAccessible(true);
        field.set(target, queue);
        return target;
    }

    private static void runTask(ClientLevelWorldStagesMixin target, Runnable task) throws Throwable {
        Operation<Void> original = arguments -> {
            assertSame(task, arguments[0]);
            ((Runnable) arguments[0]).run();
            return null;
        };
        invoke(target, "metallum$lightTask", new Class<?>[]{Runnable.class, Operation.class}, task, original);
    }

    @Test void callbackEnqueueAndExecutionOrderArePreserved() throws Throwable {
        var queue = new ArrayDeque<Runnable>();
        var target = fixture(queue);
        var calls = new ArrayList<Integer>();
        Runnable second = () -> calls.add(2);
        Runnable first = () -> { calls.add(1); queue.add(second); };
        Operation<Void> enqueue = arguments -> { queue.add((Runnable) arguments[0]); return null; };
        invoke(target, "metallum$lightEnqueue", new Class<?>[]{Runnable.class, Operation.class}, first, enqueue);
        assertSame(first, queue.peek());
        Operation<Void> poll = arguments -> {
            // The original policy is supplied unchanged; callbacks can enqueue.
            int budget = Math.max(10, queue.size() / 10);
            for (int i = 0; i < budget; i++) {
                Runnable task = queue.poll();
                if (task == null) break;
                try { runTask(target, task); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            }
            return null;
        };
        invoke(target, "metallum$lightPoll", new Class<?>[]{Operation.class}, poll);
        assertEquals(List.of(1, 2), calls);
        assertTrue(queue.isEmpty());
    }

    @Test void failurePropagatesIdenticallyWithoutConsumingFollowingTask() throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var target = fixture(queue);
        var failure = new IllegalStateException("original callback failure");
        Runnable task = () -> { throw failure; };
        Runnable following = () -> fail("following callback must remain queued");
        queue.add(task); queue.add(following);
        Operation<Void> poll = arguments -> {
            Runnable next = queue.poll();
            try { runTask(target, next); }
            catch (RuntimeException original) { throw original; }
            catch (Throwable unexpected) { throw new AssertionError(unexpected); }
            return null;
        };
        var thrown = assertThrows(IllegalStateException.class,
                () -> invoke(target, "metallum$lightPoll", new Class<?>[]{Operation.class}, poll));
        assertSame(failure, thrown);
        assertEquals(1, queue.size());
        assertSame(following, queue.peek());
    }

    @Test void enclosingUpdateCallsOriginalExactlyOnceOnFailure() throws Exception {
        var target = fixture(new ArrayDeque<>());
        var failure = new IllegalArgumentException("original update failure");
        int[] count = {0};
        Operation<Void> original = arguments -> { count[0]++; throw failure; };
        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> invoke(target, "metallum$lightUpdate", new Class<?>[]{Operation.class}, original)));
        assertEquals(1, count[0]);
    }
}
