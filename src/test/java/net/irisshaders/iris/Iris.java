package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Headless test-classpath facade for Iris shader-pack parsing. */
public class Iris {
    public static final String MODID = "iris";
    public static final String MODNAME = "Iris";
    public static final IrisLogging logger = new IrisLogging("Iris-MetalFrontendTest");
    public static final boolean IS_FOOL = false;
    public static NamespacedId lastDimension;
    public static boolean testing = true;

    private static final Map<String, String> OPTION_QUEUE = new HashMap<>();
    private static final PipelineManager PIPELINE_MANAGER = new PipelineManager(id -> null);
    private static IrisConfig config;
    private static Path scratchDirectory;

    public static synchronized IrisConfig getIrisConfig() {
        if (config == null) {
            try {
                Path directory = scratchDirectory();
                config = new IrisConfig(
                        directory.resolve("iris.properties"),
                        directory.resolve("iris-exclusions.properties")
                );
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot create headless Iris config", exception);
            }
        }
        return config;
    }

    public static Map<String, String> getShaderPackOptionQueue() {
        return OPTION_QUEUE;
    }

    /** Headless tests have no live world pipeline; production Iris owns this state. */
    public static PipelineManager getPipelineManager() {
        return PIPELINE_MANAGER;
    }

    public static synchronized Path getShaderpacksDirectory() {
        try {
            return scratchDirectory().resolve("shaderpacks");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String getVersion() {
        return "1.11.2-metal-frontend-test";
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

    private static synchronized Path scratchDirectory() throws IOException {
        if (scratchDirectory == null) {
            scratchDirectory = Files.createTempDirectory("metallum-iris-frontend-test");
            Files.createDirectories(scratchDirectory.resolve("shaderpacks"));
        }
        return scratchDirectory;
    }
}
