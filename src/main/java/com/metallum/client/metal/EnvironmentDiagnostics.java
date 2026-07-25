package com.metallum.client.metal;

import com.metallum.Metallum;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动时环境兼容性诊断工具。
 *
 * <p>面向 PojavLauncher + Java 25 运行环境，检测已知的环境层兼容性问题：
 * <ul>
 *   <li>Java 25 移除了 {@code sun.java2d.SurfaceManagerFactory}（JDK-8355611），
 *       CacioCTC（PojavLauncher 的无头 AWT 实现）依赖此类。</li>
 *   <li>LWJGL 版本过低（&lt; 3.3.3）无法识别 Java 25 的 JNI 版本，可能导致崩溃。</li>
 *   <li>JNA 版本过低（&lt; 5.17.0）不满足 fabric-loader 0.19.3 在 Java 25 +
 *       Android 16KB 页大小下的要求。</li>
 *   <li>Java 25 下需要额外的原生访问 JVM 标志以抑制 Unsafe/JNI 警告。</li>
 * </ul>
 *
 * <p>所有检测均为非阻断式：任何异常都被捕获并以 {@code WARN} 日志记录，
 * 不会向外传播，不会阻断游戏启动。LWJGL/JNA 类通过反射访问，避免在编译期
 * 因缺失依赖而抛出 {@link ClassNotFoundException}。
 */
public final class EnvironmentDiagnostics {

    private EnvironmentDiagnostics() {
    }

    /**
     * 运行全部环境诊断检测。每个子检测独立捕获异常，互不影响。
     */
    public static void runAll() {
        Metallum.LOGGER.info("[MetalUniversal] Running environment diagnostics...");
        try {
            checkSurfaceManagerFactory();
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: checkSurfaceManagerFactory threw unexpectedly: {}", t.toString());
        }
        try {
            checkLwjglVersion();
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: checkLwjglVersion threw unexpectedly: {}", t.toString());
        }
        try {
            checkJnaVersion();
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: checkJnaVersion threw unexpectedly: {}", t.toString());
        }
        try {
            checkNativeAccessFlags();
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: checkNativeAccessFlags threw unexpectedly: {}", t.toString());
        }
        Metallum.LOGGER.info("[MetalUniversal] Environment diagnostics complete.");
    }

    /**
     * 检测 {@code sun.java2d.SurfaceManagerFactory} 是否存在。
     * Java 25（JDK-8355611）移除了此类，CacioCTC 依赖它。
     */
    private static void checkSurfaceManagerFactory() {
        try {
            Class.forName("sun.java2d.SurfaceManagerFactory");
            Metallum.LOGGER.info("[MetalUniversal] ENV INFO: sun.java2d.SurfaceManagerFactory is present; CacioCTC headless AWT should function.");
        } catch (ClassNotFoundException e) {
            // 类被删除（Java 25 移除了该类）
            Metallum.LOGGER.error("[MetalUniversal] ENV ERROR: sun.java2d.SurfaceManagerFactory was removed in Java 25 (JDK-8355611).\n"
                    + "CacioCTC (PojavLauncher's headless AWT) requires this class. Please use MojoLauncher beta or Amethyst-Android which ship a Java 25-compatible runtime, or stay on Java 21.");
        } catch (Throwable t) {
            // 其他反射/链接异常（防御性，理论上 Class.forName 不会抛出）
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: SurfaceManagerFactory diagnostic failed: {}", t.toString());
        }
    }

    /**
     * 通过反射获取 LWJGL 版本，检测是否满足 Java 25 的最低要求（3.3.3）。
     * 若 LWJGL 不在类路径上则静默跳过。
     */
    private static void checkLwjglVersion() {
        try {
            final Class<?> versionClass;
            try {
                versionClass = Class.forName("org.lwjgl.Version");
            } catch (ClassNotFoundException e) {
                // LWJGL 未在类路径上，静默跳过（不视为错误）
                Metallum.LOGGER.debug("[MetalUniversal] LWJGL not on classpath, skipping LWJGL version check.");
                return;
            }
            final Method getVersion = versionClass.getMethod("getVersion");
            final Object result = getVersion.invoke(null);
            final String version = result != null ? result.toString() : "unknown";
            if (isVersionBelow(version, "3.3.3")) {
                Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: LWJGL {} detected, but Java 25 requires LWJGL 3.3.3+ (ideally 3.4.2). Older versions cannot recognize Java 25 JNI version, which may cause crashes.", version);
            } else {
                Metallum.LOGGER.info("[MetalUniversal] ENV INFO: LWJGL {} detected; compatible with Java 25.", version);
            }
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: LWJGL version check failed: {}", t.toString());
        }
    }

    /**
     * 通过反射获取 JNA 版本，检测是否满足 fabric-loader 0.19.3 的要求（5.17.0）。
     * 若 JNA 不在类路径上则静默跳过。
     */
    private static void checkJnaVersion() {
        try {
            final Class<?> versionClass;
            try {
                versionClass = Class.forName("com.sun.jna.Version");
            } catch (ClassNotFoundException e) {
                // JNA 未在类路径上，静默跳过
                Metallum.LOGGER.debug("[MetalUniversal] JNA not on classpath, skipping JNA version check.");
                return;
            }
            final Field versionField = versionClass.getField("VERSION");
            final Object result = versionField.get(null);
            final String version = result != null ? result.toString() : "unknown";
            if (isVersionBelow(version, "5.17.0")) {
                Metallum.LOGGER.info("[MetalUniversal] ENV INFO: JNA {} detected, but fabric-loader 0.19.3 requires 5.17.0 for Java 25 + Android 16KB page size. This is a non-fatal warning from PojavLauncher's MCDL module.", version);
            } else {
                Metallum.LOGGER.info("[MetalUniversal] ENV INFO: JNA {} detected; meets fabric-loader 0.19.3 requirement (>=5.17.0).", version);
            }
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: JNA version check failed: {}", t.toString());
        }
    }

    /**
     * 检测 Java 主版本号，若为 25 及以上则提示所需的原生访问 JVM 标志。
     */
    private static void checkNativeAccessFlags() {
        try {
            final int major = Runtime.version().version().get(0);
            if (major >= 25) {
                Metallum.LOGGER.info("[MetalUniversal] ENV INFO: Java {} detected. To suppress Unsafe/JNI warnings, add JVM flags: --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow", major);
            }
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: Native access flag check failed: {}", t.toString());
        }
    }

    /**
     * 解析版本字符串前 3 段数字（主.次.修订），忽略后缀如 -SNAPSHOT 或 build 信息。
     */
    private static int[] parseVersionParts(final String version) {
        if (version == null) {
            return new int[0];
        }
        final List<Integer> parts = new ArrayList<>(3);
        final Matcher matcher = Pattern.compile("\\d+").matcher(version);
        while (matcher.find() && parts.size() < 3) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                break;
            }
        }
        final int[] result = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            result[i] = parts.get(i);
        }
        return result;
    }

    /**
     * 比较 {@code actual} 是否严格低于 {@code threshold}（按 主.次.修订 数值比较，
     * 缺失段视为 0）。无法解析的版本号视为"不低于"，以避免误报过低警告。
     */
    private static boolean isVersionBelow(final String actual, final String threshold) {
        final int[] a = parseVersionParts(actual);
        if (a.length == 0) {
            return false;
        }
        final int[] t = parseVersionParts(threshold);
        final int len = Math.max(a.length, t.length);
        for (int i = 0; i < len; i++) {
            final int av = i < a.length ? a[i] : 0;
            final int tv = i < t.length ? t[i] : 0;
            if (av < tv) {
                return true;
            }
            if (av > tv) {
                return false;
            }
        }
        return false;
    }
}
