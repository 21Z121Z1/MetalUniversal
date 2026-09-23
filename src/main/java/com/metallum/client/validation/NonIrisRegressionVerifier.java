package com.metallum.client.validation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Offline fail-closed verifier for the shaders-off control/treatment gate.
 *
 * <p>The control has the Iris Metal semantic switch disabled. The treatment
 * requests the semantic layer while Iris shaders remain disabled. Both must
 * therefore resolve to the same ordinary Metal + Sodium/vanilla renderer and
 * produce byte-identical final targets from an identical frozen scene.</p>
 */
public final class NonIrisRegressionVerifier {
    private static final Pattern FRAME_METADATA = Pattern.compile("frame-\\d{5}\\.json");
    private static final Pattern FRAME_ERROR = Pattern.compile("frame-\\d{5}\\.error\\.txt");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> SCENE_IDENTITY_FIELDS = List.of(
            "scenarioId",
            "worldName",
            "worldSnapshotSha256",
            "requestedPlayerName",
            "requestedPlayerUuid",
            "playerName",
            "playerUuid"
    );
    private static final List<String> SCENE_RECEIPT_FIELDS = List.of(
            "scenarioId",
            "worldName",
            "worldSnapshotSha256",
            "requestedGameDirectory",
            "gameDirectory",
            "workingDirectory",
            "requestedPlayerName",
            "requestedPlayerUuid",
            "playerName",
            "playerUuid"
    );

    /**
     * Fields whose equality proves the capture grain before raw buffers are
     * compared. Role-specific Iris fields are validated separately.
     */
    private static final List<String> MATCHED_FRAME_FIELDS = List.of(
            "schema",
            "deviceBackend",
            "scenarioId",
            "worldName",
            "worldSnapshotSha256",
            "frame",
            "width",
            "height",
            "format",
            "bytes",
            "targetLabel",
            "rowOrder",
            "pngAlpha",
            "hudRequested",
            "metalFxMode",
            "frameGenerationRequested",
            "objectMotionProducerRequested",
            "fixedClockTicks",
            "observedOverworldClockTicks",
            "observedDefaultClockTicks",
            "freezeSimulationRequested",
            "integratedServerScenarioConfigured",
            "serverSimulationFrozen",
            "clientSimulationFrozen",
            "fixedWeather",
            "observedRainLevel",
            "observedThunderLevel",
            "sceneReadinessRequested",
            "stableSceneFramesRequired",
            "stableSceneMillisRequired",
            "sceneReady",
            "sceneStartIrisResetAttempted",
            "sceneStartIrisResetCompleted",
            "loadedChunkCount",
            "visibleChunkCount",
            "terrainRenderComplete",
            "sceneStartLoadedChunkCount",
            "sceneStartVisibleChunkCount",
            "sceneStartEntityCount",
            "sceneStartEntityStateSha256",
            "renderEntityCount",
            "renderEntityStateSha256",
            "irisFrameCounter",
            "irisFrameTime",
            "irisFrameTimeCounter",
            "fixedIrisFrameMillis",
            "fixedCamera",
            "observedPlayer"
    );

    private NonIrisRegressionVerifier() {
    }

    public static void main(final String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: NonIrisRegressionVerifier <control-dir> <treatment-dir> <report.json>"
            );
        }
        Path reportPath = Path.of(args[2]).toAbsolutePath().normalize();
        VerificationResult result = verify(Path.of(args[0]), Path.of(args[1]));
        Path parent = reportPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                reportPath,
                GSON.toJson(result) + "\n",
                StandardCharsets.UTF_8
        );
        if (!result.passed()) {
            throw new IllegalStateException(
                    "Non-Iris regression gate failed with " + result.failures().size()
                            + " problem(s); report=" + reportPath
            );
        }
        System.out.println(
                "Non-Iris regression gate: PASS (" + result.frames().size()
                        + " byte-identical stable frames); report=" + reportPath
        );
    }

    static VerificationResult verify(final Path controlInput, final Path treatmentInput)
            throws IOException {
        Path control = controlInput.toAbsolutePath().normalize();
        Path treatment = treatmentInput.toAbsolutePath().normalize();
        List<String> failures = new ArrayList<>();
        List<FrameComparison> comparisons = new ArrayList<>();

        JsonObject controlSession = readObject(control.resolve("session.json"), failures, "control session");
        JsonObject treatmentSession =
                readObject(treatment.resolve("session.json"), failures, "treatment session");
        validateSession(controlSession, false, failures, "control");
        validateSession(treatmentSession, true, failures, "treatment");
        compareFields(
                controlSession,
                treatmentSession,
                SCENE_IDENTITY_FIELDS,
                failures,
                "session scene identity"
        );
        requireDistinctGameDirectories(controlSession, treatmentSession, failures);

        Map<String, Path> controlFrames = frameMetadata(control, failures, "control");
        Map<String, Path> treatmentFrames = frameMetadata(treatment, failures, "treatment");
        if (controlFrames.size() < 2 || treatmentFrames.size() < 2) {
            failures.add(
                    "gate requires at least two stable frames per lane; control="
                            + controlFrames.size() + ", treatment=" + treatmentFrames.size()
            );
        }
        if (!controlFrames.keySet().equals(treatmentFrames.keySet())) {
            Set<String> missingControl = new LinkedHashSet<>(treatmentFrames.keySet());
            missingControl.removeAll(controlFrames.keySet());
            Set<String> missingTreatment = new LinkedHashSet<>(controlFrames.keySet());
            missingTreatment.removeAll(treatmentFrames.keySet());
            failures.add(
                    "frame metadata sets differ; missingControl=" + missingControl
                            + ", missingTreatment=" + missingTreatment
            );
        }

        validateCompletedFrames(controlSession, controlFrames.keySet(), failures, "control");
        validateCompletedFrames(treatmentSession, treatmentFrames.keySet(), failures, "treatment");

        Set<String> common = new LinkedHashSet<>(controlFrames.keySet());
        common.retainAll(treatmentFrames.keySet());
        for (String metadataName : common) {
            JsonObject controlFrame =
                    readObject(controlFrames.get(metadataName), failures, "control " + metadataName);
            JsonObject treatmentFrame =
                    readObject(treatmentFrames.get(metadataName), failures, "treatment " + metadataName);
            validateFrameRole(controlFrame, false, failures, "control " + metadataName);
            validateFrameRole(treatmentFrame, true, failures, "treatment " + metadataName);
            compareFields(
                    controlSession,
                    controlFrame,
                    SCENE_RECEIPT_FIELDS,
                    failures,
                    "control session/" + metadataName
            );
            compareFields(
                    treatmentSession,
                    treatmentFrame,
                    SCENE_RECEIPT_FIELDS,
                    failures,
                    "treatment session/" + metadataName
            );
            compareFields(
                    controlFrame,
                    treatmentFrame,
                    MATCHED_FRAME_FIELDS,
                    failures,
                    metadataName
            );

            String stem = metadataName.substring(0, metadataName.length() - ".json".length());
            Path controlBytes = control.resolve(stem + ".bin");
            Path treatmentBytes = treatment.resolve(stem + ".bin");
            ByteComparison bytes = compareBytes(controlBytes, treatmentBytes, failures, stem);
            compareEntityRows(
                    control.resolve(stem + "-entities.txt"),
                    treatment.resolve(stem + "-entities.txt"),
                    failures,
                    stem
            );
            comparisons.add(new FrameComparison(
                    integer(controlFrame, "frame", -1),
                    bytes.byteCount(),
                    bytes.controlSha256(),
                    bytes.treatmentSha256(),
                    bytes.differingBytes(),
                    bytes.firstDifferentByte(),
                    bytes.maxByteDelta(),
                    bytes.exact()
            ));
        }

        rejectCaptureErrors(control, failures, "control");
        rejectCaptureErrors(treatment, failures, "treatment");
        return new VerificationResult(
                failures.isEmpty() ? "passed" : "failed",
                control.toString(),
                treatment.toString(),
                List.copyOf(comparisons),
                List.copyOf(failures)
        );
    }

    private static void validateSession(
            final JsonObject session,
            final boolean semanticRequested,
            final List<String> failures,
            final String lane
    ) {
        if (session == null) {
            return;
        }
        requireEquals(session, "schema", 1, failures, lane + " session");
        requireEquals(session, "status", "passed", failures, lane + " session");
        requireEquals(session, "failedCaptures", 0, failures, lane + " session");
        validateShadersOffReceipt(session, semanticRequested, failures, lane + " session");
        requireEquals(session, "sceneReady", true, failures, lane + " session");
        requireEquals(
                session,
                "sceneStartIrisResetCompleted",
                true,
                failures,
                lane + " session"
        );
        validateSceneReceipt(session, failures, lane + " session");
    }

    private static void validateFrameRole(
            final JsonObject frame,
            final boolean semanticRequested,
            final List<String> failures,
            final String label
    ) {
        if (frame == null) {
            return;
        }
        validateShadersOffReceipt(frame, semanticRequested, failures, label);
        requireEquals(frame, "format", "RGBA8_UNORM", failures, label);
        requireEquals(frame, "rowOrder", "backend-native-copy-order", failures, label);
        requireEquals(frame, "hudRequested", false, failures, label);
        requireEquals(frame, "sceneReady", true, failures, label);
        requireEquals(frame, "terrainRenderComplete", true, failures, label);
        validateSceneReceipt(frame, failures, label);
    }

    private static void validateSceneReceipt(
            final JsonObject receipt,
            final List<String> failures,
            final String label
    ) {
        requireNonBlank(receipt, "scenarioId", failures, label);
        requireNonBlank(receipt, "worldName", failures, label);
        requireNonBlank(receipt, "requestedGameDirectory", failures, label);
        requireNonBlank(receipt, "gameDirectory", failures, label);
        requireNonBlank(receipt, "workingDirectory", failures, label);
        requireNonBlank(receipt, "requestedPlayerName", failures, label);
        requireNonBlank(receipt, "requestedPlayerUuid", failures, label);
        requireNonBlank(receipt, "playerName", failures, label);
        requireNonBlank(receipt, "playerUuid", failures, label);
        String requestedDirectory = string(receipt, "requestedGameDirectory");
        String actualDirectory = string(receipt, "gameDirectory");
        String workingDirectory = string(receipt, "workingDirectory");
        if (requestedDirectory != null && actualDirectory != null
                && !requestedDirectory.equals(actualDirectory)) {
            failures.add(
                    label + " requestedGameDirectory does not match actual gameDirectory:"
                            + " requested=" + requestedDirectory + ", actual=" + actualDirectory
            );
        }
        if (requestedDirectory != null && workingDirectory != null
                && !requestedDirectory.equals(workingDirectory)) {
            failures.add(
                    label + " requestedGameDirectory does not match JVM workingDirectory:"
                            + " requested=" + requestedDirectory + ", working=" + workingDirectory
            );
        }
        String requestedPlayerName = string(receipt, "requestedPlayerName");
        String requestedPlayerUuid = string(receipt, "requestedPlayerUuid");
        String playerName = string(receipt, "playerName");
        String playerUuid = string(receipt, "playerUuid");
        if (requestedPlayerName != null && playerName != null
                && !requestedPlayerName.equals(playerName)) {
            failures.add(
                    label + " requestedPlayerName does not match playerName:"
                            + " requested=" + requestedPlayerName + ", actual=" + playerName
            );
        }
        if (requestedPlayerUuid != null && playerUuid != null
                && !requestedPlayerUuid.equals(playerUuid)) {
            failures.add(
                    label + " requestedPlayerUuid does not match playerUuid:"
                            + " requested=" + requestedPlayerUuid + ", actual=" + playerUuid
            );
        }
        String snapshot = string(receipt, "worldSnapshotSha256");
        if (snapshot == null || !SHA256.matcher(snapshot).matches()) {
            failures.add(
                    label + " worldSnapshotSha256 must be a lowercase SHA-256, found "
                            + display(receipt == null ? null : receipt.get("worldSnapshotSha256"))
            );
        }
    }

    private static void requireDistinctGameDirectories(
            final JsonObject control,
            final JsonObject treatment,
            final List<String> failures
    ) {
        String controlDirectory = string(control, "gameDirectory");
        String treatmentDirectory = string(treatment, "gameDirectory");
        if (controlDirectory != null && controlDirectory.equals(treatmentDirectory)) {
            failures.add(
                    "control and treatment must use distinct isolated game directories: "
                            + controlDirectory
            );
        }
        String controlWorking = string(control, "workingDirectory");
        String treatmentWorking = string(treatment, "workingDirectory");
        if (controlWorking != null && controlWorking.equals(treatmentWorking)) {
            failures.add(
                    "control and treatment must use distinct isolated working directories: "
                            + controlWorking
            );
        }
    }

    private static void validateShadersOffReceipt(
            final JsonObject receipt,
            final boolean semanticRequested,
            final List<String> failures,
            final String label
    ) {
        requireEquals(receipt, "deviceBackend", "Metal", failures, label);
        requireEquals(
                receipt,
                "irisSemanticRequested",
                semanticRequested,
                failures,
                label
        );
        requireEquals(receipt, "irisShadersEnabled", false, failures, label);
        requireEquals(receipt, "irisPackPresent", false, failures, label);
        requireNull(receipt, "irisPackName", failures, label);
        requireEquals(receipt, "irisMetalGeneration", -1, failures, label);
        requireEquals(receipt, "metalFxMode", "OFF", failures, label);
        requireEquals(receipt, "frameGenerationRequested", false, failures, label);
        requireEquals(receipt, "objectMotionProducerRequested", false, failures, label);

        JsonElement pipeline = receipt.get("irisPipelineClass");
        if (pipeline == null || pipeline.isJsonNull()) {
            failures.add(label + " missing live shaders-off Iris pipeline identity");
        } else {
            String name = pipeline.getAsString();
            if (!name.endsWith(".VanillaRenderingPipeline")) {
                failures.add(label + " uses non-vanilla Iris pipeline " + name);
            }
        }
    }

    private static void compareFields(
            final JsonObject control,
            final JsonObject treatment,
            final List<String> fields,
            final List<String> failures,
            final String label
    ) {
        if (control == null || treatment == null) {
            return;
        }
        for (String field : fields) {
            JsonElement left = control.get(field);
            JsonElement right = treatment.get(field);
            if (left == null || right == null) {
                failures.add(
                        label + " missing comparison field " + field
                                + " (control=" + display(left) + ", treatment=" + display(right) + ")"
                );
            } else if (!left.equals(right)) {
                failures.add(
                        label + " field " + field + " differs: control=" + display(left)
                                + ", treatment=" + display(right)
                );
            }
        }
    }

    private static ByteComparison compareBytes(
            final Path control,
            final Path treatment,
            final List<String> failures,
            final String label
    ) throws IOException {
        if (!Files.isRegularFile(control) || !Files.isRegularFile(treatment)) {
            failures.add(
                    label + " missing raw final target (control=" + Files.isRegularFile(control)
                            + ", treatment=" + Files.isRegularFile(treatment) + ")"
            );
            return new ByteComparison(0L, "", "", 0L, -1L, 0, false);
        }
        byte[] left = Files.readAllBytes(control);
        byte[] right = Files.readAllBytes(treatment);
        String leftSha = sha256(left);
        String rightSha = sha256(right);
        if (left.length != right.length) {
            failures.add(
                    label + " raw final-target lengths differ: control=" + left.length
                            + ", treatment=" + right.length
            );
            return new ByteComparison(
                    Math.max(left.length, right.length),
                    leftSha,
                    rightSha,
                    Math.max(left.length, right.length),
                    Math.min(left.length, right.length),
                    255,
                    false
            );
        }
        long differences = 0L;
        long first = -1L;
        int maxDelta = 0;
        for (int index = 0; index < left.length; index++) {
            int delta = Math.abs((left[index] & 0xff) - (right[index] & 0xff));
            if (delta != 0) {
                differences++;
                if (first < 0L) {
                    first = index;
                }
                maxDelta = Math.max(maxDelta, delta);
            }
        }
        if (differences != 0L) {
            failures.add(
                    label + " raw final target differs at " + differences + "/" + left.length
                            + " bytes; first=" + first + ", maxDelta=" + maxDelta
            );
        }
        return new ByteComparison(
                left.length,
                leftSha,
                rightSha,
                differences,
                first,
                maxDelta,
                differences == 0L
        );
    }

    private static void compareEntityRows(
            final Path control,
            final Path treatment,
            final List<String> failures,
            final String label
    ) throws IOException {
        if (!Files.isRegularFile(control) || !Files.isRegularFile(treatment)) {
            failures.add(
                    label + " missing exact entity rows (control=" + Files.isRegularFile(control)
                            + ", treatment=" + Files.isRegularFile(treatment) + ")"
            );
            return;
        }
        byte[] left = Files.readAllBytes(control);
        byte[] right = Files.readAllBytes(treatment);
        if (!Arrays.equals(left, right)) {
            failures.add(
                    label + " exact entity rows differ: controlSha=" + sha256(left)
                            + ", treatmentSha=" + sha256(right)
            );
        }
    }

    private static void rejectCaptureErrors(
            final Path directory,
            final List<String> failures,
            final String lane
    ) throws IOException {
        if (!Files.isDirectory(directory)) {
            failures.add(lane + " capture directory is missing: " + directory);
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> FRAME_ERROR.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .forEach(path -> failures.add(lane + " contains capture error " + path.getFileName()));
        }
    }

    private static Map<String, Path> frameMetadata(
            final Path directory,
            final List<String> failures,
            final String lane
    ) throws IOException {
        Map<String, Path> result = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            failures.add(lane + " capture directory is missing: " + directory);
            return result;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> FRAME_METADATA.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .forEach(path -> result.put(path.getFileName().toString(), path));
        }
        return result;
    }

    private static JsonObject readObject(
            final Path path,
            final List<String> failures,
            final String label
    ) {
        if (!Files.isRegularFile(path)) {
            failures.add(label + " is missing: " + path);
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                failures.add(label + " is not a JSON object: " + path);
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException exception) {
            failures.add(label + " cannot be read: " + path + " (" + exception + ")");
            return null;
        }
    }

    private static void validateCompletedFrames(
            final JsonObject session,
            final Set<String> metadataNames,
            final List<String> failures,
            final String lane
    ) {
        if (session == null) {
            return;
        }
        JsonElement completedElement = session.get("completedFrames");
        if (completedElement == null || !completedElement.isJsonArray()) {
            failures.add(lane + " session has no completedFrames array");
            return;
        }
        Set<Integer> completed = integers(completedElement.getAsJsonArray(), failures, lane);
        Set<Integer> metadataFrames = new LinkedHashSet<>();
        for (String name : metadataNames) {
            metadataFrames.add(Integer.parseInt(name.substring(6, 11)));
        }
        if (!completed.equals(metadataFrames)) {
            failures.add(
                    lane + " completedFrames do not match metadata: completed=" + completed
                            + ", metadata=" + metadataFrames
            );
        }
    }

    private static Set<Integer> integers(
            final JsonArray array,
            final List<String> failures,
            final String label
    ) {
        Set<Integer> values = new LinkedHashSet<>();
        for (JsonElement element : array) {
            try {
                values.add(element.getAsInt());
            } catch (RuntimeException exception) {
                failures.add(label + " completedFrames contains non-integer " + display(element));
            }
        }
        return values;
    }

    private static void requireEquals(
            final JsonObject object,
            final String field,
            final Object expected,
            final List<String> failures,
            final String label
    ) {
        JsonElement actual = object.get(field);
        boolean matches;
        if (actual == null || actual.isJsonNull()) {
            matches = expected == null;
        } else if (expected instanceof Boolean value) {
            matches = actual.isJsonPrimitive() && actual.getAsBoolean() == value;
        } else if (expected instanceof Number value) {
            matches = actual.isJsonPrimitive()
                    && Double.compare(actual.getAsDouble(), value.doubleValue()) == 0;
        } else {
            matches = actual.isJsonPrimitive() && actual.getAsString().equals(expected);
        }
        if (!matches) {
            failures.add(
                    label + " field " + field + " expected " + expected
                            + ", found " + display(actual)
            );
        }
    }

    private static void requireNull(
            final JsonObject object,
            final String field,
            final List<String> failures,
            final String label
    ) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonNull()) {
            failures.add(label + " field " + field + " must be explicit null, found " + display(value));
        }
    }

    private static void requireNonBlank(
            final JsonObject object,
            final String field,
            final List<String> failures,
            final String label
    ) {
        String value = string(object, field);
        if (value == null || value.isBlank()) {
            failures.add(
                    label + " field " + field + " must be a non-blank string, found "
                            + display(object == null ? null : object.get(field))
            );
        }
    }

    private static String string(final JsonObject object, final String field) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int integer(final JsonObject object, final String field, final int fallback) {
        if (object == null) {
            return fallback;
        }
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static String display(final JsonElement element) {
        return element == null ? "<missing>" : element.toString();
    }

    private static String sha256(final byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK has no SHA-256 provider", impossible);
        }
    }

    public record VerificationResult(
            String status,
            String controlDirectory,
            String treatmentDirectory,
            List<FrameComparison> frames,
            List<String> failures
    ) {
        public boolean passed() {
            return "passed".equals(this.status);
        }
    }

    public record FrameComparison(
            int frame,
            long bytes,
            String controlSha256,
            String treatmentSha256,
            long differingBytes,
            long firstDifferentByte,
            int maxByteDelta,
            boolean exact
    ) {
    }

    private record ByteComparison(
            long byteCount,
            String controlSha256,
            String treatmentSha256,
            long differingBytes,
            long firstDifferentByte,
            int maxByteDelta,
            boolean exact
    ) {
    }
}
