package com.metallum.client.validation.contract;

import com.google.gson.stream.JsonReader;
import com.metallum.client.validation.storage.ValidationStorageBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Runs in a restricted child heap; never loads a GPU or a document-sized JSON tree. */
public final class RenderTraceRecorderMemoryFixture {
    private static final String PAYLOAD = "source-雨雪-\\\"-".repeat(12);

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        verify(root.resolve("complete"), 35_000, 64L * 1024 * 1024, 128L * 1024 * 1024, true);
        verify(root.resolve("file-limit"), 20_000, 32_768, 128L * 1024 * 1024, false);
        verify(root.resolve("shared-limit"), 20_000, 64L * 1024 * 1024, 4096, false);
        System.out.println("BOUNDED_MANIFEST_MEMORY_PASS");
    }

    private static void verify(Path root, int count, long fileLimit, long sharedLimit, boolean complete) throws Exception {
        System.setProperty("metallum.renderContract.maxManifestBytes", Long.toString(fileLimit));
        var budget = ValidationStorageBudget.shared(root, sharedLimit);
        var recorder = new RenderTraceRecorder(root, "memory-fixture", "fixed-source", 4, 8, count + 1, budget);
        recorder.beginFrame(0);
        long pass = recorder.beginPass("synthetic/streaming", PassType.RENDER, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "pipeline", List.of("vertex", "fragment"), Map.of());
        for (int i = 0; i < count; i++) recorder.recordProducer(pass, ProducerType.DRAW, "pipeline",
                Map.of("index", Integer.toString(i), "payload", PAYLOAD), Map.of(), List.of("color0"));
        recorder.endPass(pass);
        recorder.endFrame(0);
        recorder.close();
        if (recorder.manifestComplete() != complete || recorder.manifestFinalized() != complete)
            throw new AssertionError("Invalid manifest completion after streaming");
        Path manifest = root.resolve("pass-manifest.json");
        if (Files.exists(root.resolve("pass-manifest.json.partial"))) throw new AssertionError("Leaked partial file");
        if (complete && Files.size(manifest) < 10L * 1024 * 1024) throw new AssertionError("Fixture is too small");
        if (!complete && Files.size(manifest) > 4096) throw new AssertionError("Unbounded failure summary");
        long declared = -1, required = -1;
        int actual = 0;
        boolean finalized = false, omitted = false;
        try (JsonReader json = new JsonReader(Files.newBufferedReader(manifest))) {
            json.beginObject();
            while (json.hasNext()) switch (json.nextName()) {
                case "producerCount" -> declared = json.nextLong();
                case "manifestComplete" -> finalized = json.nextBoolean();
                case "tracePayloadOmitted" -> omitted = json.nextBoolean();
                case "requiredManifestBytes" -> required = json.nextLong();
                case "passes" -> {
                    json.beginArray();
                    while (json.hasNext()) {
                        json.beginObject();
                        while (json.hasNext()) {
                            if (!json.nextName().equals("producers")) { json.skipValue(); continue; }
                            json.beginArray();
                            while (json.hasNext()) {
                                int index = -1; String value = null, parameterIndex = null;
                                json.beginObject();
                                while (json.hasNext()) switch (json.nextName()) {
                                    case "producerIndex" -> index = json.nextInt();
                                    case "parameters" -> {
                                        json.beginObject();
                                        while (json.hasNext()) switch (json.nextName()) {
                                            case "index" -> parameterIndex = json.nextString();
                                            case "payload" -> value = json.nextString();
                                            default -> json.skipValue();
                                        }
                                        json.endObject();
                                    }
                                    default -> json.skipValue();
                                }
                                json.endObject();
                                if (index != actual || !Integer.toString(actual).equals(parameterIndex) || !PAYLOAD.equals(value))
                                    throw new AssertionError("Dropped/reordered/changed producer " + actual);
                                actual++;
                            }
                            json.endArray();
                        }
                        json.endObject();
                    }
                    json.endArray();
                }
                default -> json.skipValue();
            }
            json.endObject();
        }
        if (declared != count || finalized != complete || actual != (complete ? count : 0)
                || (!complete && (!omitted || required <= Math.min(fileLimit, sharedLimit))))
            throw new AssertionError("Streaming oracle lost the full count or authorized truncated evidence");
        if (complete && budget.artifactBytes() != Files.size(manifest)) throw new AssertionError("Incorrect byte accounting");
    }
}
