package com.metallum.client.platform;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** macOS per-thread QoS assignment. Every role is opt-in. */
public final class AppleThreadQos {
    private static final int BACKGROUND = 0x09;
    private static final int UTILITY = 0x11;
    private static final int DEFAULT = 0x15;
    private static final int USER_INITIATED = 0x19;
    private static final int USER_INTERACTIVE = 0x21;

    private static volatile MethodHandle setter;
    private static volatile boolean lookupAttempted;

    private AppleThreadQos() {}

    public static boolean enabled(String role) {
        return level(role) != 0;
    }

    public static boolean apply(String role) {
        int level = level(role);
        if (level == 0) return false;
        MethodHandle handle = setter();
        if (handle == null) return false;
        try {
            return (int) handle.invokeExact(level, 0) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int level(String role) {
        String value = System.getProperty("metallum.opt.qos." + role, "").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "background" -> BACKGROUND;
            case "utility" -> UTILITY;
            case "default" -> DEFAULT;
            case "initiated", "user-initiated" -> USER_INITIATED;
            case "interactive", "user-interactive" -> USER_INTERACTIVE;
            default -> 0;
        };
    }

    private static MethodHandle setter() {
        MethodHandle current = setter;
        if (current != null || lookupAttempted) return current;
        synchronized (AppleThreadQos.class) {
            if (setter != null || lookupAttempted) return setter;
            lookupAttempted = true;
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = linker.defaultLookup();
                setter = lookup.find("pthread_set_qos_class_self_np")
                        .map(symbol -> linker.downcallHandle(
                                symbol,
                                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)
                        ))
                        .orElse(null);
            } catch (RuntimeException ignored) {
                setter = null;
            }
            return setter;
        }
    }
}
