package com.metallum.client.validation;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NonIrisRegressionVerifierTest {
    private static final int[] FRAMES = {160, 220};

    @TempDir
    Path temporary;

    @Test
    void identicalShadersOffControlAndTreatmentPass() throws IOException {
        Path control = writeLane("control", false);
        Path treatment = writeLane("treatment", true);

        NonIrisRegressionVerifier.VerificationResult result =
                NonIrisRegressionVerifier.verify(control, treatment);

        assertTrue(result.passed(), () -> String.join("\n", result.failures()));
        assertTrue(result.frames().stream().allMatch(NonIrisRegressionVerifier.FrameComparison::exact));
    }

    @Test
    void activeIrisGenerationFailsClosed() throws IOException {
        Path control = writeLane("control", false);
        Path treatment = writeLane("treatment", true);
        mutateJson(treatment.resolve("session.json"), json -> {
            json.addProperty("irisPipelineClass", "com.metallum.client.metal.render.MetalWorldRenderingPipeline");
            json.addProperty("irisMetalGeneration", 7);
        });

        NonIrisRegressionVerifier.VerificationResult result =
                NonIrisRegressionVerifier.verify(control, treatment);

        assertFalse(result.passed());
        assertTrue(
                result.failures().stream().anyMatch(problem -> problem.contains("non-vanilla Iris pipeline"))
        );
        assertTrue(
                result.failures().stream().anyMatch(problem -> problem.contains("irisMetalGeneration"))
        );
    }

    @Test
    void sceneOrFinalTargetDifferenceCannotBeTolerated() throws IOException {
        Path control = writeLane("control", false);
        Path treatment = writeLane("treatment", true);
        mutateJson(treatment.resolve("frame-00160.json"), json ->
                json.addProperty("loadedChunkCount", 3_724)
        );
        byte[] bytes = Files.readAllBytes(treatment.resolve("frame-00220.bin"));
        bytes[3] = (byte) (bytes[3] + 1);
        Files.write(treatment.resolve("frame-00220.bin"), bytes);

        NonIrisRegressionVerifier.VerificationResult result =
                NonIrisRegressionVerifier.verify(control, treatment);

        assertFalse(result.passed());
        assertTrue(
                result.failures().stream().anyMatch(problem -> problem.contains("loadedChunkCount"))
        );
        assertTrue(
                result.failures().stream().anyMatch(problem -> problem.contains("raw final target differs"))
        );
    }

    @Test
    void worldSnapshotAndGameDirectoryIsolationAreMandatory() throws IOException {
        Path control = writeLane("control", false);
        Path treatment = writeLane("treatment", true);
        mutateJson(treatment.resolve("session.json"), json -> {
            json.addProperty(
                    "worldSnapshotSha256",
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            );
            json.addProperty("gameDirectory", "/isolated/control");
        });

        NonIrisRegressionVerifier.VerificationResult result =
                NonIrisRegressionVerifier.verify(control, treatment);

        assertFalse(result.passed());
        assertTrue(
                result.failures().stream().anyMatch(problem ->
                        problem.contains("worldSnapshotSha256"))
        );
        assertTrue(
                result.failures().stream().anyMatch(problem ->
                        problem.contains("distinct isolated game directories"))
        );
    }

    @Test
    void declaredAndObservedGameDirectoriesMustMatch() throws IOException {
        Path control = writeLane("control", false);
        Path treatment = writeLane("treatment", true);
        mutateJson(treatment.resolve("session.json"), json ->
                json.addProperty("gameDirectory", "/unexpected/run")
        );

        NonIrisRegressionVerifier.VerificationResult result =
                NonIrisRegressionVerifier.verify(control, treatment);

        assertFalse(result.passed());
        assertTrue(
                result.failures().stream().anyMatch(problem ->
                        problem.contains("requestedGameDirectory does not match actual gameDirectory"))
        );
    }

    @Test
    void declaredAndObservedWorkingDirectoriesMustMatch() throws IOException {
        Path control = writeLane("control", false);
        Path treatment = writeLane("treatment", true);
        mutateJson(treatment.resolve("session.json"), json ->
                json.addProperty("workingDirectory", "/unexpected/run")
        );

        NonIrisRegressionVerifier.VerificationResult result =
                NonIrisRegressionVerifier.verify(control, treatment);

        assertFalse(result.passed());
        assertTrue(
                result.failures().stream().anyMatch(problem ->
                        problem.contains("requestedGameDirectory does not match JVM workingDirectory"))
        );
    }

    @Test
    void declaredAndObservedPlayerIdentityMustMatch() throws IOException {
        Path control = writeLane("control", false);
        Path treatment = writeLane("treatment", true);
        mutateJson(treatment.resolve("session.json"), json ->
                json.addProperty("playerUuid", "00000000-0000-0000-0000-000000000000")
        );

        NonIrisRegressionVerifier.VerificationResult result =
                NonIrisRegressionVerifier.verify(control, treatment);

        assertFalse(result.passed());
        assertTrue(
                result.failures().stream().anyMatch(problem ->
                        problem.contains("requestedPlayerUuid does not match playerUuid"))
        );
    }

    private Path writeLane(final String name, final boolean semanticRequested) throws IOException {
        Path directory = temporary.resolve(name);
        Files.createDirectories(directory);

        JsonObject session = shadersOffReceipt(semanticRequested, name);
        session.addProperty("schema", 1);
        session.addProperty("status", "passed");
        session.addProperty("failedCaptures", 0);
        session.addProperty("sceneReady", true);
        session.addProperty("sceneStartIrisResetCompleted", true);
        JsonArray completed = new JsonArray();
        for (int frame : FRAMES) {
            completed.add(frame);
        }
        session.add("completedFrames", completed);
        writeJson(directory.resolve("session.json"), session);

        for (int frame : FRAMES) {
            JsonObject metadata = frameReceipt(frame, semanticRequested, name);
            String stem = String.format("frame-%05d", frame);
            writeJson(directory.resolve(stem + ".json"), metadata);
            Files.write(directory.resolve(stem + ".bin"), frameBytes(frame));
            Files.write(
                    directory.resolve(stem + "-entities.txt"),
                    List.of(
                            "minecraft:player|00000000-0000-0000-0000-000000000001"
                                    + "|0x1.0p0|0x1.0p1|0x1.0p2"
                    ),
                    StandardCharsets.UTF_8
            );
        }
        return directory;
    }

    private static JsonObject frameReceipt(
            final int frame,
            final boolean semanticRequested,
            final String lane
    ) {
        JsonObject json = shadersOffReceipt(semanticRequested, lane);
        json.addProperty("schema", 1);
        json.addProperty("frame", frame);
        json.addProperty("width", 2);
        json.addProperty("height", 1);
        json.addProperty("format", "RGBA8_UNORM");
        json.addProperty("bytes", 8);
        json.addProperty("targetLabel", "MainTarget");
        json.addProperty("rowOrder", "backend-native-copy-order");
        json.addProperty("pngAlpha", "forced-opaque; raw RGBA retained in .bin");
        json.addProperty("hudRequested", false);
        json.addProperty("fixedClockTicks", 108_500);
        json.addProperty("observedOverworldClockTicks", 108_500);
        json.addProperty("observedDefaultClockTicks", 108_500);
        json.addProperty("freezeSimulationRequested", true);
        json.addProperty("integratedServerScenarioConfigured", true);
        json.addProperty("serverSimulationFrozen", true);
        json.addProperty("clientSimulationFrozen", true);
        json.addProperty("fixedWeather", "clear");
        json.addProperty("observedRainLevel", 0.0);
        json.addProperty("observedThunderLevel", 0.0);
        json.addProperty("sceneReadinessRequested", true);
        json.addProperty("stableSceneFramesRequired", 240);
        json.addProperty("stableSceneMillisRequired", 8_000);
        json.addProperty("sceneReady", true);
        json.addProperty("sceneStartIrisResetAttempted", true);
        json.addProperty("sceneStartIrisResetCompleted", true);
        json.addProperty("loadedChunkCount", 3_725);
        json.addProperty("visibleChunkCount", 10_716);
        json.addProperty("terrainRenderComplete", true);
        json.addProperty("sceneStartLoadedChunkCount", 3_725);
        json.addProperty("sceneStartVisibleChunkCount", 10_716);
        json.addProperty("sceneStartEntityCount", 65);
        json.addProperty("sceneStartEntityStateSha256", "scene");
        json.addProperty("renderEntityCount", 65);
        json.addProperty("renderEntityStateSha256", "scene");
        json.addProperty("irisFrameCounter", frame + 1);
        json.addProperty("irisFrameTime", 0.016);
        json.addProperty("irisFrameTimeCounter", frame * 0.016);
        json.addProperty("fixedIrisFrameMillis", 16);
        JsonObject camera = new JsonObject();
        camera.addProperty("x", 1.0);
        camera.addProperty("y", 2.0);
        camera.addProperty("z", 3.0);
        camera.addProperty("yaw", 4.0);
        camera.addProperty("pitch", 5.0);
        json.add("fixedCamera", camera);
        json.add("observedPlayer", camera.deepCopy());
        return json;
    }

    private static JsonObject shadersOffReceipt(
            final boolean semanticRequested,
            final String lane
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("deviceBackend", "Metal");
        json.addProperty("scenarioId", "non-iris-dusk-v1");
        json.addProperty("worldName", "New World");
        json.addProperty(
                "worldSnapshotSha256",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        json.addProperty("requestedGameDirectory", "/isolated/" + lane);
        json.addProperty("gameDirectory", "/isolated/" + lane);
        json.addProperty("workingDirectory", "/isolated/" + lane);
        json.addProperty("requestedPlayerName", "MetalRegression");
        json.addProperty("requestedPlayerUuid", "8f16930a-42ad-4f9b-9d59-02698f26b145");
        json.addProperty("playerName", "MetalRegression");
        json.addProperty("playerUuid", "8f16930a-42ad-4f9b-9d59-02698f26b145");
        json.addProperty("irisSemanticRequested", semanticRequested);
        json.addProperty("irisShadersEnabled", false);
        json.addProperty("irisPackPresent", false);
        json.add("irisPackName", JsonNull.INSTANCE);
        json.addProperty(
                "irisPipelineClass",
                "net.irisshaders.iris.pipeline.VanillaRenderingPipeline"
        );
        json.addProperty("irisMetalGeneration", -1);
        json.addProperty("metalFxMode", "OFF");
        json.addProperty("frameGenerationRequested", false);
        json.addProperty("objectMotionProducerRequested", false);
        return json;
    }

    private static byte[] frameBytes(final int frame) {
        return new byte[]{
                (byte) frame,
                2,
                3,
                4,
                5,
                6,
                7,
                8
        };
    }

    private static void mutateJson(final Path path, final JsonMutation mutation) throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        mutation.apply(json);
        writeJson(path, json);
    }

    private static void writeJson(final Path path, final JsonObject json) throws IOException {
        Files.writeString(
                path,
                new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(json) + "\n",
                StandardCharsets.UTF_8
        );
    }

    @FunctionalInterface
    private interface JsonMutation {
        void apply(JsonObject json);
    }
}
