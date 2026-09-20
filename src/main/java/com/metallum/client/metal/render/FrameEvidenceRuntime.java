package com.metallum.client.metal.render;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.terrain.VanillaTerrainWorkTelemetry;
import com.metallum.client.validation.storage.ValidationStorageBudget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Optional observer; no alternate renderer, native module, wait, or per-frame file I/O. */
public final class FrameEvidenceRuntime {
    public static final boolean ENABLED = Boolean.getBoolean("metallum.frameEvidence.enabled");
    private static final FrameEvidenceRecorder RECORDER = ENABLED
            ? new FrameEvidenceRecorder(Integer.getInteger("metallum.frameEvidence.capacity", 16_384), System::nanoTime)
            : null;
    private static final JsonObject IDENTITY = new JsonObject();
    private static String validationStatus = "unvalidated";

    private FrameEvidenceRuntime() { }

    public static MethodHandle instrument(String symbol, MethodHandle target) {
        // Disabled calls retain the original downcall handle: no per-ABI branch/timer/allocation.
        return ENABLED ? RECORDER.instrument(symbol, target) : target;
    }

    public static void beginFrame(boolean advanceGameTime) {
        if (!ENABLED) return;
        if (!IDENTITY.has("build")) initializeIdentity();
        Minecraft client = Minecraft.getInstance();
        RECORDER.beginFrame(client.level != null);
        int irisGeneration = FabricLoader.getInstance().isModLoaded("iris")
                ? IrisMetalPipelineOverrides.activeGenerationForDiagnostics() : -1;
        JsonObject context = new JsonObject();
        context.addProperty("advanceGameTime", advanceGameTime);
        context.addProperty("drawableWidth", client.getWindow().getWidth());
        context.addProperty("drawableHeight", client.getWindow().getHeight());
        context.addProperty("renderDistance", client.options.getEffectiveRenderDistance());
        context.addProperty("irisGeneration", irisGeneration);
        context.addProperty("metalTier", MetalDevice.current() == null ? "unavailable"
                : MetalDevice.current().metal4MainRenderer() ? "metal4-main" : "metal3-main");
        RECORDER.context(context);
        if (irisGeneration >= 0) {
            // This proves an active shader generation, not a submitted draw or displayed frame.
            RECORDER.producer("iris-active-generation");
        }
    }

    public static void endFrame() {
        if (ENABLED) RECORDER.endFrame();
    }

    public static void producer(String source) {
        if (ENABLED) RECORDER.producer(source);
    }

    public static void terrainBatchEncoded(long terrainFrameIndex) {
        if (ENABLED) RECORDER.terrainBatchEncoded(terrainFrameIndex);
    }

    public static FrameEvidenceRecorder.Submission commandBuffer(long submitIndex) {
        return ENABLED ? RECORDER.commandBuffer(submitIndex) : null;
    }

    public static void submitted(FrameEvidenceRecorder.Submission submission) {
        if (ENABLED) RECORDER.submitted(submission);
    }

    public static void presentationRequested(FrameEvidenceRecorder.Submission submission, long nativeId) {
        if (ENABLED) RECORDER.presentationRequested(submission, nativeId);
    }

    public static void completed(FrameEvidenceRecorder.Submission submission, boolean success, double start, double end,
                                 MemorySegment commandBuffer) {
        if (!ENABLED || submission == null) return;
        RECORDER.nativePresentationId(submission, MetalNativeBridge.commandBufferPresentationId(commandBuffer));
        RECORDER.nativeEncoding(submission, MetalNativeBridge.commandBufferEncodingCounters(commandBuffer));
        RECORDER.drawableWait(submission, MetalNativeBridge.commandBufferDrawableWaitNanos(commandBuffer));
        RECORDER.completed(submission, success, start, end);
    }

    public static void validationFinished(String status) {
        if (ENABLED) validationStatus = status;
    }

    public static void nativeLoaded(Path path) throws IOException {
        if (ENABLED) IDENTITY.addProperty("nativeSha256", sha256(path));
    }

    /** Called after the existing device shutdown drain, never adds a profiling-induced GPU wait. */
    public static void writeAfterDrain() {
        if (!ENABLED) return;
        Path output = Path.of(System.getProperty("metallum.frameEvidence.output",
                System.getProperty("metallum.validation.output", "build/frame-evidence") + "/frame-evidence.json"));
        try {
            long[] presentationIds = RECORDER.presentationIds();
            RECORDER.presented(presentationIds, MetalNativeBridge.presentationEvidence(presentationIds));
            JsonObject report = RECORDER.snapshot(IDENTITY);
            report.addProperty("validationStatus", validationStatus);
            report.addProperty("shutdownDrained", true);
            Files.createDirectories(output.toAbsolutePath().getParent());
            writeTerrainEvidence(output.toAbsolutePath().getParent(), report);
            Files.writeString(output, new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report) + "\n");
        } catch (IOException exception) {
            Metallum.LOGGER.error("Could not export frame evidence to {}", output, exception);
        }
    }

    private static void writeTerrainEvidence(Path directory, JsonObject frameReport) {
        if (!Boolean.getBoolean(VanillaTerrainWorkTelemetry.ENABLE_PROPERTY)) return;
        JsonArray files = new JsonArray();
        frameReport.add("terrainEvidenceFiles", files);
        try {
            String sourceSha = IDENTITY.getAsJsonObject("build").get("sourceSha").getAsString();
            String trialId = IDENTITY.get("trialId").getAsString();
            // Shutdown drains GPU work; it does not certify a completed validation trial.
            boolean passed = "passed".equals(validationStatus);
            var reports = VanillaTerrainWorkTelemetry.reports(sourceSha, trialId, passed,
                    passed ? null : validationStatus);
            var storage = ValidationStorageBudget.shared(directory);
            for (var entry : reports.entrySet()) {
                String name = "terrain-work-epoch-" + entry.getKey() + ".json";
                storage.writeString(directory.resolve(name),
                        new GsonBuilder().serializeNulls().create().toJson(entry.getValue()) + "\n");
                files.add(name);
            }
        } catch (IOException | RuntimeException exception) {
            frameReport.addProperty("terrainEvidenceUnavailableReason", exception.toString());
            Metallum.LOGGER.error("Could not export terrain evidence to {}", directory, exception);
        }
    }

    private static void initializeIdentity() {
        try (var stream = FrameEvidenceRuntime.class.getResourceAsStream("/metallum-build-identity.json")) {
            IDENTITY.add("build", stream == null ? new JsonObject()
                    : JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException exception) {
            IDENTITY.add("build", new JsonObject());
        }
        IDENTITY.addProperty("requestedSourceSha", System.getProperty("metallum.validation.sourceCommit", "unknown"));
        IDENTITY.addProperty("trialId", System.getProperty("metallum.frameEvidence.trialId", "unspecified"));
        IDENTITY.addProperty("vanillaTerrainWorkEventsRequested", Boolean.getBoolean("metallum.terrain.vanillaWorkEvents"));
        IDENTITY.addProperty("world", System.getProperty("metallum.validation.world", "unspecified"));
        IDENTITY.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        IDENTITY.addProperty("architecture", System.getProperty("os.arch"));
        IDENTITY.addProperty("java", System.getProperty("java.version"));
        IDENTITY.addProperty("backend", RenderSystem.getDevice().getDeviceInfo().backendName());
        JsonObject mods = new JsonObject();
        FabricLoader.getInstance().getAllMods().stream()
                .sorted(java.util.Comparator.comparing(mod -> mod.getMetadata().getId()))
                .forEach(mod -> mods.addProperty(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
        IDENTITY.add("mods", mods);
        try {
            Path location = Path.of(FrameEvidenceRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            IDENTITY.addProperty("javaArtifactSha256", Files.isRegularFile(location) ? sha256(location) : "unavailable-dev-classes");
        } catch (Exception exception) {
            IDENTITY.addProperty("javaArtifactSha256", "unavailable");
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] bytes = new byte[65_536];
                for (int count; (count = input.read(bytes)) != -1;) digest.update(bytes, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
