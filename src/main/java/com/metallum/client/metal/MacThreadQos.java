package com.metallum.client.metal;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in Darwin pthread QoS assignment performed by each target thread itself. */
public final class MacThreadQos {
    private static final ConcurrentHashMap<String, AtomicLong> attempts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> successes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> failures = new ConcurrentHashMap<>();
    private static volatile MethodHandle setSelf;

    private MacThreadQos() {
    }

    public static boolean configured(String role) {
        return qosClass(role) != 0;
    }

    public static int qosClass(String role) {
        String value = System.getProperty("metallum.opt.qos." + role);
        if (value == null) return 0;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "background" -> 0x09;
            case "utility" -> 0x11;
            case "default" -> 0x15;
            case "initiated", "user-initiated" -> 0x19;
            case "interactive", "user-interactive" -> 0x21;
            default -> 0;
        };
    }

    public static void apply(String role) {
        int qos = qosClass(role);
        if (qos == 0) return;
        attempts.computeIfAbsent(role, ignored -> new AtomicLong()).incrementAndGet();
        try {
            int result = (int) setter().invokeExact(qos, 0);
            if (result == 0) {
                successes.computeIfAbsent(role, ignored -> new AtomicLong()).incrementAndGet();
            } else {
                failures.computeIfAbsent(role, ignored -> new AtomicLong()).incrementAndGet();
            }
        } catch (Throwable failure) {
            failures.computeIfAbsent(role, ignored -> new AtomicLong()).incrementAndGet();
        }
    }

    public static long attempts(String role) {
        return counter(attempts, role);
    }

    public static long successes(String role) {
        return counter(successes, role);
    }

    public static long failures(String role) {
        return counter(failures, role);
    }

    private static long counter(ConcurrentHashMap<String, AtomicLong> map, String role) {
        AtomicLong value = map.get(role);
        return value == null ? 0L : value.get();
    }

    private static MethodHandle setter() {
        MethodHandle current = setSelf;
        if (current != null) return current;
        synchronized (MacThreadQos.class) {
            if (setSelf == null) {
                Linker linker = Linker.nativeLinker();
                setSelf = linker.downcallHandle(
                        linker.defaultLookup().find("pthread_set_qos_class_self_np").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
                );
            }
            return setSelf;
        }
    }
}
