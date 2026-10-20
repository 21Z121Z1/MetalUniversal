package com.metallum.client.validation.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class RenderContractCompressedEvidenceTest {
    @TempDir Path root;
    private static JsonObject payload() {
        return JsonParser.parseString("""
                {"schemaVersion":1,"runId":"fixture","gitCommit":"fixed-source","status":"passed","manifestComplete":true,
                 "frameCount":1,"frames":[7],"passCount":1,"passes":[{"semanticPassId":"world/cloud","parameters":{"unicode":"雨雪"}}],
                 "resourceCount":1,"resources":[{"stableKey":"color0@1"}],"producerCount":3,"droppedEvents":0,
                 "forcedClosedPassCount":0,"invalidPassReferenceCount":0,"producerBudgetExceeded":false,
                 "limits":{"maxFrames":4,"maxPasses":8,"maxProducers":16,"maxManifestBytes":65536},
                 "storageBudget":{"exceeded":false,"artifactBytes":1024,"maxBytes":65536}}
                """).getAsJsonObject();
    }
    private JsonObject publish() throws Exception {
        var body=payload();byte[] raw=body.toString().getBytes(StandardCharsets.UTF_8);
        var bytes=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(bytes)){gzip.write(raw);}
        byte[] compressed=bytes.toByteArray();Files.write(root.resolve("pass-manifest.payload.json.gz"),compressed);
        var index=body.deepCopy();for(String name:new String[]{"frames","passes","resources"})index.remove(name);
        index.addProperty("schemaVersion",2);index.addProperty("payloadEncoding","gzip-json-v1");index.addProperty("tracePayloadOmitted",false);
        index.getAsJsonObject("limits").addProperty("maxUncompressedManifestBytes",1_048_576);
        var descriptor=new JsonObject();descriptor.addProperty("path","pass-manifest.payload.json.gz");
        descriptor.addProperty("bytes",compressed.length);descriptor.addProperty("uncompressedBytes",raw.length);
        descriptor.addProperty("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(compressed)));
        index.add("payload",descriptor);Files.writeString(root.resolve("pass-manifest.json"),index.toString());return index;
    }
    @Test void independentArchiveFixtureRetainsEveryOriginalFieldAndUnicode() throws Exception {
        assertEquals(payload(),RenderContractCompressedEvidence.read(root,publish()));
    }
    @Test void missingDigestPathExtentHeaderAndDecodingLimitFailuresCannotPass() throws Exception {
        for(int fault=0;fault<9;fault++) {
            var index=publish();var p=index.getAsJsonObject("payload");
            switch(fault) {
                case 0->p.addProperty("sha256","0".repeat(64));
                case 1->p.addProperty("path","../outside.gz");
                case 2->p.addProperty("bytes",p.get("bytes").getAsLong()+1);
                case 3->p.addProperty("uncompressedBytes",p.get("uncompressedBytes").getAsLong()-1);
                case 4->p.addProperty("uncompressedBytes",p.get("uncompressedBytes").getAsLong()+1);
                case 5->index.addProperty("producerCount",4);
                case 6->index.getAsJsonObject("limits").addProperty("maxUncompressedManifestBytes",2L*1024*1024*1024);
                case 7->index.getAsJsonObject("limits").addProperty("maxManifestBytes",p.get("bytes").getAsLong());
                case 8->Files.delete(root.resolve("pass-manifest.payload.json.gz"));
            }
            assertThrows(IOException.class,()->RenderContractCompressedEvidence.read(root,index),"fault="+fault);
        }
    }
    @Test void corruptionCannotBeHiddenByRehashingItsDescriptor() throws Exception {
        for(boolean truncate:new boolean[]{false,true}) {
            var index=publish();Path path=root.resolve("pass-manifest.payload.json.gz");byte[] bytes=Files.readAllBytes(path);
            if(truncate)bytes=java.util.Arrays.copyOf(bytes,bytes.length-4);else bytes[bytes.length-8]^=1;
            Files.write(path,bytes);index.getAsJsonObject("payload").addProperty("bytes",bytes.length);
            index.getAsJsonObject("payload").addProperty("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertThrows(IOException.class,()->RenderContractCompressedEvidence.read(root,index));
        }
    }
    @Test void symlinkAndFailedStorageProofRemainRejected() throws Exception {
        var index=publish();Path path=root.resolve("pass-manifest.payload.json.gz"),other=root.resolve("other.gz");
        Files.move(path,other);Files.createSymbolicLink(path,other.getFileName());
        var linked=index;
        assertThrows(IOException.class,()->RenderContractCompressedEvidence.read(root,linked));Files.delete(path);
        index=publish();index.getAsJsonObject("storageBudget").addProperty("exceeded",true);var failed=index;
        assertThrows(IOException.class,()->RenderContractCompressedEvidence.read(root,failed));
    }
}
