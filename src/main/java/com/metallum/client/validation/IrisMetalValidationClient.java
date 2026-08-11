package com.metallum.client.validation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalWorldRenderingPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Opt-in, input-free lifecycle validation for the generic Iris Metal path.
 *
 * <p>The driver is inert unless {@code metallum.iris.validation.enabled=true}.
 * It is called from the client render loop so reload and toggle operations are
 * serialized with the same thread that owns the Iris pipeline. The final
 * target readback remains owned by {@code IrisMetalRuntimeReceipts}; this
 * file records control operations and their observed postconditions.</p>
 */
public final class IrisMetalValidationClient {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final boolean ENABLED = Boolean.getBoolean("metallum.iris.validation.enabled");
    private static final int RELOAD_FRAME = Integer.getInteger(
            "metallum.iris.validation.reloadFrame", -1
    );
    private static final int DISABLE_FRAME = Integer.getInteger(
            "metallum.iris.validation.disableFrame", -1
    );
    private static final int ENABLE_FRAME = Integer.getInteger(
            "metallum.iris.validation.enableFrame", -1
    );
    private static final int STOP_FRAME = Integer.getInteger(
            "metallum.iris.validation.stopFrame", 720
    );
    private static final int WORLD_TIMEOUT_FRAMES = Integer.getInteger(
            "metallum.iris.validation.worldTimeoutFrames", 3600
    );
    private static final int SHADOW_DISTANCE = Integer.getInteger(
            "metallum.iris.validation.shadowDistance", -1
    );
    private static final Path CONTROL_RECEIPT = Path.of(System.getProperty(
            "metallum.iris.validation.controlReceipt",
            "build/iris-runtime/iris-validation-control.jsonl"
    )).toAbsolutePath().normalize();

    private static BufferedWriter writer;
    private static boolean started;
    private static boolean finished;
    private static boolean failed;
    private static boolean activeObserved;
    private static boolean reloadCompleted;
    private static boolean disableCompleted;
    private static boolean enableCompleted;
    private static boolean worldEntered;
    private static int observedFrames;
    private static int frame = -1;

    private IrisMetalValidationClient() {
    }

    public static void beforeFrame(final boolean renderLevel) {
        if (!ENABLED || finished) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            ensureStarted();
            observedFrames++;
            if (minecraft.level == null || minecraft.player == null) {
                if (observedFrames >= WORLD_TIMEOUT_FRAMES) {
                    fail(minecraft, "world-not-entered", null);
                }
                return;
            }
            if (!worldEntered) {
                worldEntered = true;
                event("world.entered");
            }
            if (!renderLevel) {
                return;
            }
            frame++;
            if (isActivePipeline()) {
                if (!activeObserved) {
                    activeObserved = true;
                    event("pipeline-active");
                }
            }
            if (frame == RELOAD_FRAME) {
                reload(minecraft);
            }
            if (frame == DISABLE_FRAME) {
                toggle(minecraft, false);
            }
            if (frame == ENABLE_FRAME) {
                toggle(minecraft, true);
            }
        } catch (Throwable failure) {
            fail(minecraft, "control-exception", failure);
        }
    }

    public static void afterFrame(final boolean renderLevel) {
        if (!ENABLED || !renderLevel || finished || frame < 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (frame < STOP_FRAME) {
            return;
        }
        if (RELOAD_FRAME >= 0 && !reloadCompleted) {
            fail(minecraft, "reload-not-completed", null);
            return;
        }
        if (DISABLE_FRAME >= 0 && !disableCompleted) {
            fail(minecraft, "disable-not-completed", null);
            return;
        }
        if (ENABLE_FRAME >= 0 && !enableCompleted) {
            fail(minecraft, "enable-not-completed", null);
            return;
        }
        if (!isActivePipeline()) {
            fail(minecraft, "final-pipeline-inactive", null);
            return;
        }
        finished = true;
        event("validation-passed");
        result("passed", null);
        closeWriter();
        Metallum.LOGGER.info(
                "[MetalUniversal/Iris] lifecycle validation passed at frame {}; stopping client",
                frame
        );
        minecraft.stop();
    }

    private static void reload(final Minecraft minecraft) {
        if (!isActivePipeline()) {
            fail(minecraft, "reload-before-active-pipeline", null);
            return;
        }
        event("reload.begin");
        String packBefore = currentPackName();
        try {
            Iris.reload();
            reloadCompleted = isActivePipeline();
            eventWithState("reload.end", packBefore);
            if (!reloadCompleted) {
                fail(minecraft, "reload-produced-no-active-pipeline", null);
            }
        } catch (Throwable failure) {
            fail(minecraft, "reload.failed", failure);
        }
    }

    private static void toggle(final Minecraft minecraft, final boolean enabled) {
        if (enabled && isActivePipeline()) {
            fail(minecraft, "enable-before-disable", null);
            return;
        }
        if (!enabled && !isActivePipeline()) {
            fail(minecraft, "disable-without-active-pipeline", null);
            return;
        }
        event(enabled ? "disable-enable.enable.begin" : "disable-enable.disable.begin");
        String packBefore = currentPackName();
        try {
            Iris.toggleShaders(minecraft, enabled);
            boolean postcondition = enabled == isActivePipeline();
            if (enabled) {
                enableCompleted = postcondition;
            } else {
                disableCompleted = postcondition;
            }
            eventWithState(
                    enabled ? "disable-enable.enable.end" : "disable-enable.disable.end",
                    packBefore
            );
            if (!postcondition) {
                fail(
                        minecraft,
                        enabled ? "enable-produced-no-active-pipeline" : "disable-left-active-pipeline",
                        null
                );
            }
        } catch (Throwable failure) {
            fail(minecraft, enabled ? "enable.failed" : "disable.failed", failure);
        }
    }

    private static boolean isActivePipeline() {
        try {
            return Iris.getCurrentPack().isPresent()
                    && Iris.getPipelineManager().getPipelineNullable() instanceof MetalWorldRenderingPipeline;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static void ensureStarted() throws IOException {
        if (started) {
            return;
        }
        if (STOP_FRAME < 0 || WORLD_TIMEOUT_FRAMES <= 0
                || (DISABLE_FRAME >= 0 && ENABLE_FRAME <= DISABLE_FRAME)
                || (RELOAD_FRAME >= 0 && RELOAD_FRAME >= STOP_FRAME)
                || (DISABLE_FRAME >= 0 && DISABLE_FRAME >= STOP_FRAME)
                || (ENABLE_FRAME >= 0 && ENABLE_FRAME >= STOP_FRAME)
                || SHADOW_DISTANCE < -1 || SHADOW_DISTANCE > 32) {
            throw new IllegalArgumentException("Invalid Iris validation frame schedule");
        }
        if (SHADOW_DISTANCE >= 0) {
            // This is a validation-only override for the Iris video option.
            // It makes shadow coverage deterministic without touching the
            // user's Launcher profile or persisted options.
            IrisVideoSettings.shadowDistance = SHADOW_DISTANCE;
        }
        Path parent = CONTROL_RECEIPT.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(
                CONTROL_RECEIPT,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        started = true;
        JsonObject session = base("session");
        session.addProperty("receipt", CONTROL_RECEIPT.toString());
        session.addProperty("reloadFrame", RELOAD_FRAME);
        session.addProperty("disableFrame", DISABLE_FRAME);
        session.addProperty("enableFrame", ENABLE_FRAME);
        session.addProperty("stopFrame", STOP_FRAME);
        session.addProperty("worldTimeoutFrames", WORLD_TIMEOUT_FRAMES);
        session.addProperty("shadowDistance", SHADOW_DISTANCE);
        session.addProperty("javaVersion", System.getProperty("java.version", "unknown"));
        session.addProperty("irisVersion", safeIrisVersion());
        session.addProperty("strict", Boolean.parseBoolean(
                System.getProperty("metallum.iris.strict", "true")
        ));
        write(session);
    }

    private static void event(final String name) {
        if (!started) {
            return;
        }
        JsonObject object = base("event");
        object.addProperty("event", name);
        write(object);
    }

    private static void eventWithState(final String name, final String packBefore) {
        JsonObject object = base("event");
        object.addProperty("event", name);
        object.addProperty("packBefore", packBefore);
        object.addProperty("packAfter", currentPackName());
        object.addProperty("shadersEnabled", shadersEnabled());
        object.addProperty("activePipeline", isActivePipeline());
        write(object);
    }

    private static void result(final String status, final Throwable failure) {
        JsonObject object = base("result");
        object.addProperty("status", status);
        object.addProperty("frame", frame);
        object.addProperty("activePipeline", isActivePipeline());
        if (failure != null) {
            object.addProperty("error", failure.toString());
        }
        write(object);
    }

    private static void fail(
            final Minecraft minecraft, final String reason, final Throwable failure
    ) {
        if (finished) {
            return;
        }
        finished = true;
        failed = true;
        if (started) {
            JsonObject object = base("failure");
            object.addProperty("reason", reason);
            if (failure != null) {
                object.addProperty("error", failure.toString());
            }
            write(object);
            result("failed", failure);
            closeWriter();
        }
        Metallum.LOGGER.error("[MetalUniversal/Iris] lifecycle validation failed: {}", reason, failure);
        minecraft.stop();
    }

    private static JsonObject base(final String type) {
        JsonObject object = new JsonObject();
        object.addProperty("schema", "iris-metal-validation-control-v1");
        object.addProperty("type", type);
        object.addProperty("frame", frame);
        return object;
    }

    private static String currentPackName() {
        try {
            return Iris.getCurrentPackName();
        } catch (Throwable failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    private static String safeIrisVersion() {
        try {
            return Iris.getVersion();
        } catch (Throwable failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    private static boolean shadersEnabled() {
        try {
            return Iris.getIrisConfig().areShadersEnabled();
        } catch (Throwable failure) {
            return false;
        }
    }

    private static void write(final JsonObject object) {
        try {
            writer.write(GSON.toJson(object));
            writer.newLine();
            writer.flush();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write Iris validation receipt", failure);
        }
    }

    private static void closeWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException failure) {
            Metallum.LOGGER.error("[MetalUniversal/Iris] failed to close validation receipt", failure);
        } finally {
            writer = null;
        }
    }
}
