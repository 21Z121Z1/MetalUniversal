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
        if(args.length>1 && args[1].equals("real-scale")) {compressed(root,175_000,PAYLOAD);return;}
        verify(root.resolve("complete"), 35_000, 64L * 1024 * 1024, 128L * 1024 * 1024, true);
        verify(root.resolve("file-limit"), 20_000, 32_768, 128L * 1024 * 1024, false);
        verify(root.resolve("shared-limit"), 20_000, 64L * 1024 * 1024, 4096, false);
        compressed(root.resolve("compressed"),35_000,PAYLOAD.repeat(8));
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
        Inspection inspection;
        try(JsonReader json=new JsonReader(Files.newBufferedReader(manifest))) {inspection=inspect(json,PAYLOAD);}
        long declared=inspection.declared(),required=inspection.required();int actual=inspection.actual();
        boolean finalized=inspection.finalized(),omitted=inspection.omitted();
        if (declared != count || finalized != complete || actual != (complete ? count : 0)
                || (!complete && (!omitted || required <= Math.min(fileLimit, sharedLimit))))
            throw new AssertionError("Streaming oracle lost the full count or authorized truncated evidence");
        if (complete && budget.artifactBytes() != Files.size(manifest)) throw new AssertionError("Incorrect byte accounting");
    }
    private record Inspection(long declared,long required,int actual,boolean finalized,boolean omitted) {}
    private static Inspection inspect(JsonReader json,String expectedPayload) throws Exception {
        long declared=-1,required=-1;int actual=0;boolean finalized=false,omitted=false;
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
                                if (index != actual || !Integer.toString(actual).equals(parameterIndex) || !expectedPayload.equals(value))
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
        if(json.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw new AssertionError("Trailing payload");
        return new Inspection(declared,required,actual,finalized,omitted);
    }

    private static void compressed(Path root,int count,String expectedPayload) throws Exception {
        System.setProperty("metallum.renderContract.compressManifest","true");
        System.setProperty("metallum.renderContract.maxManifestBytes",Long.toString(64L*1024*1024));
        var budget=ValidationStorageBudget.shared(root,128L*1024*1024);
        var recorder=new RenderTraceRecorder(root,"compressed-fixture","fixed-source",4,8,count+1,budget);
        recorder.beginFrame(0);
        long pass=recorder.beginPass("synthetic/streaming",PassType.RENDER,List.of(),null,null,
                new ViewportRecord(0,0,1,1),ScissorRecord.disabled(),"pipeline",List.of("vertex","fragment"),Map.of());
        for(int i=0;i<count;i++)recorder.recordProducer(pass,ProducerType.DRAW,"pipeline",
                Map.of("index",Integer.toString(i),"payload",expectedPayload),Map.of(),List.of("color0"));
        recorder.endPass(pass);recorder.endFrame(0);recorder.close();
        if(!recorder.manifestComplete() || !recorder.manifestFinalized())throw new AssertionError("Compressed trace gate failed");
        var index=com.google.gson.JsonParser.parseString(Files.readString(root.resolve("pass-manifest.json"))).getAsJsonObject();
        if(index.get("schemaVersion").getAsInt()!=2 || index.get("producerCount").getAsInt()!=count)
            throw new AssertionError("Wrong compressed index");
        var descriptor=index.getAsJsonObject("payload");Path payload=root.resolve("pass-manifest.payload.json.gz");
        long physical=Files.size(payload)+Files.size(root.resolve("pass-manifest.json"));
        if(physical>64L*1024*1024 || budget.artifactBytes()!=physical || descriptor.get("uncompressedBytes").getAsLong()<=64L*1024*1024)
            throw new AssertionError("Compressed trace did not retain a larger-than-limit source within the unchanged physical budget");
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(payload)){byte[] bytes=new byte[8192];for(int n;(n=input.read(bytes))!=-1;)digest.update(bytes,0,n);}
        if(!java.util.HexFormat.of().formatHex(digest.digest()).equals(descriptor.get("sha256").getAsString()))throw new AssertionError("Wrong compressed digest");
        Inspection actual;
        try(var input=new java.util.zip.GZIPInputStream(Files.newInputStream(payload));
            var reader=new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8);
            var json=new JsonReader(reader)) {actual=inspect(json,expectedPayload);}
        if(actual.actual()!=count || actual.declared()!=count || !actual.finalized() || actual.omitted())
            throw new AssertionError("Compressed trace lost original producer audit semantics");
        try(var files=Files.list(root)){if(files.anyMatch(path->path.getFileName().toString().endsWith(".partial")))throw new AssertionError("Unpublished compressed checkpoint");}
        System.out.println("COMPRESSED_MANIFEST_MEMORY_PASS count="+count+" rawBytes="+descriptor.get("uncompressedBytes")+" physicalBytes="+physical);
    }

}
