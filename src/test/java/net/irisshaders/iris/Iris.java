package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * TEST-CLASSPATH SHADOW of Iris's entry class (test output precedes
 * dependency jars, so this class wins over the one in the Iris jar for
 * headless unit tests only — the real class is untouched in-game).
 *
 * <p>The real {@code Iris.<clinit>} calls
 * {@code FabricLoader.isDevelopmentEnvironment()}, which NPEs without a
 * booted Fabric launcher. The shader-pack loading + TransformPatcher paths
 * exercised by {@link com.metallum.client.metal.render.MetalIrisShaderTranslationTest}
 * touch exactly this surface (verified by a bytecode scan of the
 * shaderpack/transform packages): {@code logger}, {@code testing},
 * {@code getIrisConfig()}, {@code getShaderPackOptionQueue()},
 * {@code getShaderpacksDirectory()}. {@code testing} defaults to true: it is
 * Iris's own headless-test flag (skips registry/config access in
 * IdMap/LanguageMap). If future harness work trips a
 * {@code NoSuchMethodError} here, extend the shadow — or move the suite onto
 * fabric-loader-junit.</p>
 */
public class Iris {
    public static final String MODID = "iris";
    public static final String MODNAME = "Iris";
    public static final IrisLogging logger = new IrisLogging("Iris-MetallumTranslationTest");
    public static final boolean IS_FOOL = false;
    public static NamespacedId lastDimension = null;
    public static boolean testing = true;

    private static final Map<String, String> SHADER_PACK_OPTION_QUEUE = new HashMap<>();
    private static IrisConfig config;
    private static Path shaderpacksDirectory;

    public static synchronized IrisConfig getIrisConfig() {
        if (config == null) {
            try {
                Path dir = scratchDir();
                config = new IrisConfig(dir.resolve("iris.properties"), dir.resolve("iris-exclusions.properties"));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create headless IrisConfig", e);
            }
        }
        return config;
    }

    public static Map<String, String> getShaderPackOptionQueue() {
        return SHADER_PACK_OPTION_QUEUE;
    }

    public static synchronized Path getShaderpacksDirectory() {
        try {
            return scratchDir().resolve("shaderpacks");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String getVersion() {
        return "1.11.2-metallum-test";
    }

    public static String getFormattedVersion() {
        return getVersion();
    }

    public static String getReleaseTarget() {
        return "26.2";
    }

    public static String getBackupVersionNumber() {
        return "26.2";
    }

    private static synchronized Path scratchDir() throws IOException {
        if (shaderpacksDirectory == null) {
            shaderpacksDirectory = Files.createTempDirectory("metallum-iris-test");
            Files.createDirectories(shaderpacksDirectory.resolve("shaderpacks"));
        }
        return shaderpacksDirectory;
    }
}
