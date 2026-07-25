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
 *       CacioCTC（PojavLauncher 的无头 AWT 实现）依赖此类。<b>致命</b>（阻断启动）。</li>
 *   <li>LWJGL 版本过低（&lt; 3.3.3）无法识别 Java 25 的 JNI 版本，可能导致崩溃。<b>非致命</b>（警告）。</li>
 *   <li>JNA 版本过低（&lt; 5.17.0）不满足 fabric-loader 0.19.3 在 Java 25 +
 *       Android 16KB 页大小下的要求。<b>非致命</b>（警告，非 Java 25 移除）。</li>
 *   <li>Java 25 下 {@code sun.misc.Unsafe}（JEP 498）与 {@code System::loadLibrary}
 *       （JEP 472）的废弃/受限访问警告。<b>非致命</b>（非移除，可抑制）。</li>
 * </ul>
 *
 * <p>所有检测均为非阻断式：任何异常都被捕获并以 {@code WARN} 日志记录，
 * 不会向外传播，不会阻断游戏启动。LWJGL/JNA 类通过反射访问，避免在编译期
 * 因缺失依赖而抛出 {@link ClassNotFoundException}。
 *
 * <p>检测完成后输出汇总日志，区分<b>致命</b>（将阻断启动）与<b>非致命</b>（仅警告/版本提示）问题。
 */
public final class EnvironmentDiagnostics {

    private EnvironmentDiagnostics() {
    }

    /** 单项检测的严重级别。 */
    private enum Severity {
        /** 未检测到问题。 */
        OK,
        /** 检测到问题，但不阻断启动（仅警告/版本提示）。 */
        NON_FATAL,
        /** 检测到将阻断启动的问题。 */
        FATAL
    }

    /**
     * 运行全部环境诊断检测。每个子检测独立捕获异常，互不影响。
     * 检测完成后输出致命/非致命问题汇总日志。
     */
    public static void runAll() {
        Metallum.LOGGER.info("[MetalUniversal] Running environment diagnostics...");
        final List<String> fatal = new ArrayList<>();
        final List<String> nonFatal = new ArrayList<>();
        try {
            final Severity s = checkSurfaceManagerFactory();
            if (s == Severity.FATAL) {
                fatal.add("sun.java2d.SurfaceManagerFactory was removed in Java 25 (JDK-8355611) — CacioCTC headless AWT will fail.");
            } else if (s == Severity.NON_FATAL) {
                nonFatal.add("SurfaceManagerFactory");
            }
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: checkSurfaceManagerFactory threw unexpectedly: {}", t.toString());
            nonFatal.add("checkSurfaceManagerFactory threw: " + t);
        }
        try {
            final Severity s = checkLwjglVersion();
            if (s == Severity.FATAL) {
                fatal.add("LWJGL version too old");
            } else if (s == Severity.NON_FATAL) {
                nonFatal.add("LWJGL version below 3.3.3 (may not recognize Java 25 JNI version)");
            }
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: checkLwjglVersion threw unexpectedly: {}", t.toString());
            nonFatal.add("checkLwjglVersion threw: " + t);
        }
        try {
            final Severity s = checkJnaVersion();
            if (s == Severity.FATAL) {
                fatal.add("JNA version too old");
            } else if (s == Severity.NON_FATAL) {
                nonFatal.add("JNA version below 5.17.0 (non-fatal warning, NOT a Java 25 removal)");
            }
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: checkJnaVersion threw unexpectedly: {}", t.toString());
            nonFatal.add("checkJnaVersion threw: " + t);
        }
        try {
            final Severity s = checkNativeAccessFlags();
            if (s == Severity.FATAL) {
                fatal.add("Native access flags");
            } else if (s == Severity.NON_FATAL) {
                nonFatal.add("Java >= 25 Unsafe/loadLibrary deprecation warnings (non-fatal, not removed)");
            }
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: checkNativeAccessFlags threw unexpectedly: {}", t.toString());
            nonFatal.add("checkNativeAccessFlags threw: " + t);
        }

        // ---- 致命/非致命问题汇总 ----
        if (fatal.isEmpty()) {
            Metallum.LOGGER.info("[MetalUniversal] ENV SUMMARY: No fatal issues detected; startup will NOT be blocked by environment diagnostics.");
        } else {
            Metallum.LOGGER.error("[MetalUniversal] ENV SUMMARY: {} FATAL issue(s) WILL block startup:", fatal.size());
            for (final String f : fatal) {
                Metallum.LOGGER.error("[MetalUniversal]   - FATAL: {}", f);
            }
            Metallum.LOGGER.error("[MetalUniversal] Fix fatal issues before proceeding. For SurfaceManagerFactory removal: use MojoLauncher beta or Amethyst-Android (Java 25-compatible runtime), or stay on Java 21.");
        }
        if (nonFatal.isEmpty()) {
            Metallum.LOGGER.info("[MetalUniversal] ENV SUMMARY: No non-fatal warnings detected.");
        } else {
            Metallum.LOGGER.warn("[MetalUniversal] ENV SUMMARY: {} non-fatal issue(s) detected (do NOT block startup):", nonFatal.size());
            for (final String nf : nonFatal) {
                Metallum.LOGGER.warn("[MetalUniversal]   - non-fatal: {}", nf);
            }
        }
        Metallum.LOGGER.info("[MetalUniversal] Environment diagnostics complete.");
    }

    /**
     * 检测 {@code sun.java2d.SurfaceManagerFactory} 是否存在。
     * Java 25（JDK-8355611）移除了此类，CacioCTC 依赖它。此为<b>致命</b>问题。
     */
    private static Severity checkSurfaceManagerFactory() {
        try {
            Class.forName("sun.java2d.SurfaceManagerFactory");
            Metallum.LOGGER.info("[MetalUniversal] ENV INFO: sun.java2d.SurfaceManagerFactory is present; CacioCTC headless AWT should function.");
            return Severity.OK;
        } catch (ClassNotFoundException e) {
            // 类被删除（Java 25 移除了该类）— 致命
            Metallum.LOGGER.error("[MetalUniversal] ENV ERROR: sun.java2d.SurfaceManagerFactory was removed in Java 25 (JDK-8355611).\n"
                    + "CacioCTC (PojavLauncher's headless AWT) requires this class. Please use MojoLauncher beta or Amethyst-Android which ship a Java 25-compatible runtime, or stay on Java 21.");
            return Severity.FATAL;
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: SurfaceManagerFactory diagnostic failed: {}", t.toString());
            return Severity.NON_FATAL;
        }
    }

    /**
     * 通过反射获取 LWJGL 版本，检测是否满足 Java 25 的最低要求（3.3.3）。
     * 若 LWJGL 不在类路径上则静默跳过。此为<b>非致命</b>问题（版本过旧，警告）。
     */
    private static Severity checkLwjglVersion() {
        try {
            final Class<?> versionClass;
            try {
                versionClass = Class.forName("org.lwjgl.Version");
            } catch (ClassNotFoundException e) {
                // LWJGL 未在类路径上，静默跳过（不视为错误）
                Metallum.LOGGER.debug("[MetalUniversal] LWJGL not on classpath, skipping LWJGL version check.");
                return Severity.OK;
            }
            final Method getVersion = versionClass.getMethod("getVersion");
            final Object result = getVersion.invoke(null);
            final String version = result != null ? result.toString() : "unknown";
            if (isVersionBelow(version, "3.3.3")) {
                Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: LWJGL {} detected, but Java 25 requires LWJGL 3.3.3+ (ideally 3.4.2). Older versions cannot recognize Java 25 JNI version, which may cause crashes.", version);
                return Severity.NON_FATAL;
            } else {
                Metallum.LOGGER.info("[MetalUniversal] ENV INFO: LWJGL {} detected; compatible with Java 25.", version);
                return Severity.OK;
            }
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: LWJGL version check failed: {}", t.toString());
            return Severity.NON_FATAL;
        }
    }

    /**
     * 通过反射获取 JNA 版本，检测是否满足 fabric-loader 0.19.3 的要求（5.17.0）。
     * 若 JNA 不在类路径上则静默跳过。此为<b>非致命</b>问题。
     *
     * <p>注意：此警告<b>不是</b> Java 25 移除了什么。根因是 PojavLauncher 捆绑的
     * JNA 5.13.0 过旧，无法支持 Java 25 的 JNI 版本与 Android 16KB 页大小。
     */
    private static Severity checkJnaVersion() {
        try {
            final Class<?> versionClass;
            try {
                versionClass = Class.forName("com.sun.jna.Version");
            } catch (ClassNotFoundException e) {
                // JNA 未在类路径上，静默跳过
                Metallum.LOGGER.debug("[MetalUniversal] JNA not on classpath, skipping JNA version check.");
                return Severity.OK;
            }
            final Field versionField = versionClass.getField("VERSION");
            final Object result = versionField.get(null);
            final String version = result != null ? result.toString() : "unknown";
            if (isVersionBelow(version, "5.17.0")) {
                Metallum.LOGGER.info("[MetalUniversal] ENV INFO: JNA {} detected (PojavLauncher-bundled). fabric-loader 0.19.3 requires JNA 5.17.0 for Java 25 + Android 16KB page size.\n"
                        + "NOTE: This is NOT a Java 25 removal — Java 25 did not remove JNA. The root cause is that the bundled JNA 5.13.0 is too old to support Java 25's JNI version and Android 16KB pages.\n"
                        + "This is a non-fatal warning and does NOT block startup. To fix: use a launcher shipping JNA 5.17.0+ (MojoLauncher beta / Amethyst-Android), or stay on Java 21.", version);
                return Severity.NON_FATAL;
            } else {
                Metallum.LOGGER.info("[MetalUniversal] ENV INFO: JNA {} detected; meets fabric-loader 0.19.3 requirement (>=5.17.0).", version);
                return Severity.OK;
            }
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: JNA version check failed: {}", t.toString());
            return Severity.NON_FATAL;
        }
    }

    /**
     * 检测 Java 主版本号，若为 25 及以上则提示所需的原生访问 JVM 标志。
     *
     * <p>Java 25 下的 {@code sun.misc.Unsafe::staticFieldBase}（JEP 498 终态废弃）与
     * {@code System::loadLibrary}（JEP 472 受限原生访问）警告是<b>废弃/受限访问</b>警告，
     * <b>并非移除</b>，不阻断启动。
     */
    private static Severity checkNativeAccessFlags() {
        try {
            final int major = Runtime.version().version().get(0);
            if (major >= 25) {
                Metallum.LOGGER.info("[MetalUniversal] ENV INFO: Java {} detected. The following JVM warnings are deprecation/restricted-access warnings, NOT removals, and do NOT block startup:\n"
                        + "  - 'sun.misc.Unsafe::staticFieldBase has been called' (JEP 498: terminal deprecation of sun.misc.Unsafe — still present, just warned).\n"
                        + "  - 'java.lang.System::loadLibrary has been called' (JEP 472: restricted native access — still present, just warned).\n"
                        + "To suppress these warnings, add JVM flags: --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow", major);
                return Severity.NON_FATAL;
            }
            return Severity.OK;
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[MetalUniversal] ENV WARN: Native access flag check failed: {}", t.toString());
            return Severity.NON_FATAL;
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
