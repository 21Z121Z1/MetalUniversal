package com.metallum.client.validation.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metallum.client.validation.capture.CapturedResource;
import com.metallum.client.validation.contract.AttachmentBindingRecord;
import com.metallum.client.validation.contract.AttachmentSemantic;
import com.metallum.client.validation.contract.CaptureFormat;
import com.metallum.client.validation.contract.PassType;
import com.metallum.client.validation.contract.ProducerRecord;
import com.metallum.client.validation.contract.ProducerType;
import com.metallum.client.validation.contract.RenderPassRecord;
import com.metallum.client.validation.contract.ResourceIdentity;
import com.metallum.client.validation.contract.ScissorRecord;
import com.metallum.client.validation.contract.ViewportRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads bounded render-contract evidence from a completed run without replaying it. */
public final class RenderContractEvidenceLoader {
    private RenderContractEvidenceLoader() {
    }

    public static LoadedEvidence load(final Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        JsonObject manifest = readObject(normalized.resolve("pass-manifest.json"));
        JsonObject results = readObject(normalized.resolve("results.json"));
        validateEnvelope(manifest, "pass-manifest.json");
        validateEnvelope(results, "results.json");
        List<RenderPassRecord> passes = parsePasses(manifest.getAsJsonArray("passes"));
        CaptureLoad captureLoad = parseCaptures(
                normalized, results.getAsJsonArray("captures"), passes
        );
        return new LoadedEvidence(
                normalized,
                passes,
                captureLoad.captures(),
                manifest.get("status").getAsString(),
                results.get("status").getAsString(),
                booleanValue(manifest, "manifestComplete", false),
                numberValue(manifest, "passCount", -1),
                numberValue(manifest, "droppedEvents", -1),
                numberValue(results, "failedCaptures", -1),
                numberValue(results, "pendingCaptures", -1),
                numberValue(results, "droppedCaptures", -1),
                numberValue(results, "requestedCaptures", -1),
                numberValue(results, "completedCaptures", -1),
                captureLoad.failedEntries()
        );
    }

    private static JsonObject readObject(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Missing render-contract evidence file: " + path);
        }
        try {
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid render-contract evidence JSON: " + path, exception);
        }
    }

    private static void validateEnvelope(final JsonObject object, final String name) throws IOException {
        if (!object.has("schemaVersion") || object.get("schemaVersion").getAsInt() != 1
                || !object.has("runId") || !object.has("status")) {
            throw new IOException("Unsupported or incomplete render-contract " + name);
        }
    }

    private static List<RenderPassRecord> parsePasses(final JsonArray values) throws IOException {
        if (values == null) return List.of();
        List<RenderPassRecord> passes = new ArrayList<>();
        for (JsonElement element : values) {
            JsonObject value = element.getAsJsonObject();
            try {
                passes.add(new RenderPassRecord(
                        value.get("frameId").getAsLong(),
                        value.get("sequence").getAsInt(),
                        value.get("semanticPassId").getAsString(),
                        enumValue(PassType.class, value.get("type").getAsString()),
                        parseAttachments(value.getAsJsonArray("colorAttachments")),
                        value.get("depthAttachment").isJsonNull()
                                ? null : parseAttachment(value.getAsJsonObject("depthAttachment")),
                        value.get("stencilAttachment").isJsonNull()
                                ? null : parseAttachment(value.getAsJsonObject("stencilAttachment")),
                        parseViewport(value.getAsJsonObject("viewport")),
                        parseScissor(value.getAsJsonObject("scissor")),
                        value.get("pipelineId").getAsString(),
                        strings(value.getAsJsonArray("shaderIds")),
                        parseProducers(value.getAsJsonArray("producers")),
                        stringsMap(value.getAsJsonObject("metadata"))
                ));
            } catch (RuntimeException exception) {
                throw new IOException("Invalid logical pass in render-contract manifest", exception);
            }
        }
        return List.copyOf(passes);
    }

    private static CaptureLoad parseCaptures(
            final Path root,
            final JsonArray values,
            final List<RenderPassRecord> passes
    ) throws IOException {
        if (values == null) return new CaptureLoad(List.of(), 0);
        List<CaptureSnapshot> captures = new ArrayList<>();
        int failedEntries = 0;
        Map<FrameSemantic, List<RenderPassRecord>> passGroups = passGroups(passes);
        Map<FrameSemantic, Integer> captureOccurrences = new LinkedHashMap<>();
        for (JsonElement element : values) {
            JsonObject capture = element.getAsJsonObject();
            String captureStatus = capture.has("status")
                    ? capture.get("status").getAsString() : "unknown";
            if (!"passed".equals(captureStatus) && !"captured".equals(captureStatus)) {
                // A failed parent can still contain successfully written raw
                // resources. Keep those resources for diagnosis, but remember
                // that the run is incomplete so a byte match cannot become a
                // false PASS.
                failedEntries++;
            }
            JsonArray resources = capture.getAsJsonArray("resources");
            if (resources == null) continue;
            long frameId = capture.get("frameId").getAsLong();
            String semanticPassId = capture.get("semanticPassId").getAsString();
            FrameSemantic semantic = new FrameSemantic(frameId, semanticPassId);
            int occurrence = captureOccurrences.getOrDefault(semantic, 0);
            captureOccurrences.put(semantic, occurrence + 1);
            int sequence = traceSequence(capture);
            if (!hasTraceSequence(capture)) {
                List<RenderPassRecord> matchingPasses = passGroups.getOrDefault(semantic, List.of());
                if (occurrence < matchingPasses.size()) {
                    sequence = matchingPasses.get(occurrence).sequence();
                }
            }
            for (JsonElement resourceElement : resources) {
                JsonObject resource = resourceElement.getAsJsonObject();
                if (!resource.has("actual")) continue;
                Path actual = root.resolve(resource.get("actual").getAsString()).normalize();
                if (!actual.startsWith(root) || !Files.isRegularFile(actual)) {
                    throw new IOException("Capture artifact is outside run root or missing: " + actual);
                }
                ResourceIdentity identity = parseResource(resource.getAsJsonObject("resource"));
                CaptureFormat format = parseFormat(resource.getAsJsonObject("captureFormat"));
                byte[] bytes = Files.readAllBytes(actual);
                captures.add(new CaptureSnapshot(
                        frameId,
                        sequence,
                        semanticPassId,
                        capture.get("producerIndex").getAsInt(),
                        identity.stableKey(),
                        new CapturedResource(
                                resource.get("semanticName").getAsString(), identity, format,
                                resource.get("width").getAsInt(), resource.get("height").getAsInt(), bytes
                        )
                ));
            }
        }
        return new CaptureLoad(List.copyOf(captures), failedEntries);
    }

    private static int traceSequence(final JsonObject capture) {
        JsonObject identity = capture.has("traceIdentity") && capture.get("traceIdentity").isJsonObject()
                ? capture.getAsJsonObject("traceIdentity") : null;
        return identity != null && identity.has("passSequence") ? identity.get("passSequence").getAsInt() : 0;
    }

    private static boolean hasTraceSequence(final JsonObject capture) {
        return capture.has("traceIdentity") && capture.get("traceIdentity").isJsonObject()
                && capture.getAsJsonObject("traceIdentity").has("passSequence");
    }

    private static Map<FrameSemantic, List<RenderPassRecord>> passGroups(
            final List<RenderPassRecord> passes
    ) {
        Map<FrameSemantic, List<RenderPassRecord>> result = new LinkedHashMap<>();
        if (passes == null) return result;
        List<RenderPassRecord> ordered = new ArrayList<>(passes);
        ordered.sort(java.util.Comparator.comparingLong(RenderPassRecord::frameId)
                .thenComparingInt(RenderPassRecord::sequence));
        for (RenderPassRecord pass : ordered) {
            result.computeIfAbsent(new FrameSemantic(pass.frameId(), pass.semanticPassId()), ignored -> new ArrayList<>())
                    .add(pass);
        }
        return result;
    }

    private static List<AttachmentBindingRecord> parseAttachments(final JsonArray values) {
        if (values == null) return List.of();
        List<AttachmentBindingRecord> result = new ArrayList<>();
        for (JsonElement element : values) result.add(parseAttachment(element.getAsJsonObject()));
        return List.copyOf(result);
    }

    private static AttachmentBindingRecord parseAttachment(final JsonObject value) {
        return new AttachmentBindingRecord(
                value.get("slot").getAsInt(),
                parseResource(value.getAsJsonObject("resource")),
                enumValue(AttachmentSemantic.class, value.get("semantic").getAsString()),
                value.get("loadAction").getAsString(),
                value.get("storeAction").getAsString(),
                value.get("writable").getAsBoolean()
        );
    }

    private static List<ProducerRecord> parseProducers(final JsonArray values) {
        if (values == null) return List.of();
        List<ProducerRecord> result = new ArrayList<>();
        for (JsonElement element : values) {
            JsonObject value = element.getAsJsonObject();
            result.add(new ProducerRecord(
                    value.get("producerIndex").getAsInt(),
                    enumValue(ProducerType.class, value.get("producerType").getAsString()),
                    value.get("pipelineId").getAsString(),
                    strings(value.getAsJsonArray("shaderIds")),
                    stringsMap(value.getAsJsonObject("parameters")),
                    stringsMap(value.getAsJsonObject("boundResources")),
                    parseViewport(value.getAsJsonObject("viewport")),
                    parseScissor(value.getAsJsonObject("scissor")),
                    strings(value.getAsJsonArray("writtenAttachments"))
            ));
        }
        return List.copyOf(result);
    }

    private static ResourceIdentity parseResource(final JsonObject value) {
        return new ResourceIdentity(
                value.get("semanticName").getAsString(), value.get("runtimeId").getAsLong(),
                value.get("generation").getAsLong(), value.get("nativeHandleHashOrDebugId").getAsString(),
                value.get("format").getAsString(), value.get("width").getAsInt(),
                value.get("height").getAsInt(), value.get("depthOrLayers").getAsInt(),
                value.get("mipLevel").getAsInt(), value.get("sampleCount").getAsInt(),
                value.get("usage").getAsInt()
        );
    }

    private static CaptureFormat parseFormat(final JsonObject value) {
        return new CaptureFormat(
                value.get("name").getAsString(), value.get("bytesPerTexel").getAsInt(),
                value.get("componentCount").getAsInt(),
                enumValue(CaptureFormat.ComponentType.class, value.get("componentType").getAsString()),
                value.get("normalized").getAsBoolean(), value.get("depth").getAsBoolean(),
                value.get("stencil").getAsBoolean()
        );
    }

    private static ViewportRecord parseViewport(final JsonObject value) {
        return new ViewportRecord(value.get("x").getAsInt(), value.get("y").getAsInt(),
                value.get("width").getAsInt(), value.get("height").getAsInt());
    }

    private static ScissorRecord parseScissor(final JsonObject value) {
        return new ScissorRecord(value.get("enabled").getAsBoolean(), value.get("x").getAsInt(),
                value.get("y").getAsInt(), value.get("width").getAsInt(), value.get("height").getAsInt());
    }

    private static List<String> strings(final JsonArray values) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) result.add(value.getAsString());
        return List.copyOf(result);
    }

    private static Map<String, String> stringsMap(final JsonObject values) {
        if (values == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getAsString());
        }
        return Map.copyOf(result);
    }

    private static <E extends Enum<E>> E enumValue(final Class<E> type, final String value) {
        return Enum.valueOf(type, value);
    }

    private static int numberValue(final JsonObject object, final String name, final int fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return fallback;
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(final JsonObject object, final String name, final boolean fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return fallback;
        try {
            return object.get(name).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private record FrameSemantic(long frameId, String semanticPassId) {
    }

    private record CaptureLoad(List<CaptureSnapshot> captures, int failedEntries) {
    }

    public record LoadedEvidence(
            Path root,
            List<RenderPassRecord> passes,
            List<CaptureSnapshot> captures,
            String manifestStatus,
            String resultStatus,
            boolean manifestComplete,
            int manifestPassCount,
            int manifestDroppedEvents,
            int resultFailedCaptures,
            int resultPendingCaptures,
            int resultDroppedCaptures,
            int resultRequestedCaptures,
            int resultCompletedCaptures,
            int failedCaptureEntries
    ) {
        public LoadedEvidence {
            passes = List.copyOf(passes == null ? List.of() : passes);
            captures = List.copyOf(captures == null ? List.of() : captures);
        }

        public boolean complete() {
            return "passed".equals(manifestStatus)
                    && "passed".equals(resultStatus)
                    && manifestComplete
                    && manifestPassCount > 0
                    && manifestDroppedEvents == 0
                    && resultFailedCaptures == 0
                    && resultPendingCaptures == 0
                    && resultDroppedCaptures == 0
                    && resultRequestedCaptures > 0
                    && resultCompletedCaptures == resultRequestedCaptures
                    && !captures.isEmpty()
                    && failedCaptureEntries == 0;
        }

        public String incompleteReason() {
            List<String> reasons = new ArrayList<>();
            if (!"passed".equals(manifestStatus)) reasons.add("manifest status=" + manifestStatus);
            if (!"passed".equals(resultStatus)) reasons.add("results status=" + resultStatus);
            if (!manifestComplete) reasons.add("manifestComplete=false");
            if (manifestPassCount <= 0) reasons.add("manifest contains no logical passes");
            if (manifestDroppedEvents != 0) reasons.add("droppedEvents=" + manifestDroppedEvents);
            if (resultFailedCaptures != 0) reasons.add("failedCaptures=" + resultFailedCaptures);
            if (resultPendingCaptures != 0) reasons.add("pendingCaptures=" + resultPendingCaptures);
            if (resultDroppedCaptures != 0) reasons.add("droppedCaptures=" + resultDroppedCaptures);
            if (resultRequestedCaptures <= 0) reasons.add("no capture requests");
            if (resultCompletedCaptures != resultRequestedCaptures) {
                reasons.add("completedCaptures=" + resultCompletedCaptures
                        + "/" + resultRequestedCaptures);
            }
            if (captures.isEmpty()) reasons.add("no readable capture resources");
            if (failedCaptureEntries != 0) reasons.add("failedCaptureEntries=" + failedCaptureEntries);
            return reasons.isEmpty() ? "complete" : String.join(", ", reasons);
        }
    }
}
