package com.metallum.client.validation.contract;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metallum.client.validation.report.RenderContractEvidenceLoader;
import com.metallum.client.validation.storage.ValidationStorageBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class RenderTraceRecorderCompressionTest {
    @TempDir Path root;
    private final Map<String,String> previous=new HashMap<>();
    @BeforeEach void properties() {
        for(String name:new String[]{"metallum.renderContract.enabled","metallum.renderContract.compressManifest",
                "metallum.renderContract.maxManifestBytes","metallum.renderContract.maxUncompressedManifestBytes","metallum.validation.sourceCommit"})
            previous.put(name,System.getProperty(name));
        System.setProperty("metallum.renderContract.enabled","true");
        System.setProperty("metallum.renderContract.compressManifest","true");
        System.setProperty("metallum.renderContract.maxManifestBytes",Long.toString(64L*1024*1024));
        System.clearProperty("metallum.renderContract.maxUncompressedManifestBytes");
        System.setProperty("metallum.validation.sourceCommit","a".repeat(40));
    }
    @AfterEach void restore() {
        RenderContractRuntime.close();previous.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
    }
    private JsonObject index(Path directory) throws Exception {return JsonParser.parseString(Files.readString(directory.resolve("pass-manifest.json"))).getAsJsonObject();}
    @Test void completedCaptureRoundTripsAllAuditFieldsThroughTheIndependentLoader() throws Exception {
        RenderContractRuntime.start(root,"compressed-capture");RenderContractRuntime.beginFrame(7);
        var resource=RenderContractRuntime.identifyResource("color0",1,"texture-1","RGBA8_UNORM",1,1,1,0,1,3);
        long pass=RenderContractRuntime.beginRenderPass("synthetic/compressed",PassType.RENDER,
                List.of(new AttachmentBindingRecord(0,resource,AttachmentSemantic.COLOR,"clear","store",true)),null,null,
                new ViewportRecord(0,0,1,1),ScissorRecord.disabled(),"pipeline",List.of("fragment"),Map.of());
        RenderContractRuntime.recordProducer(pass,ProducerType.DRAW,"pipeline",Map.of("payload","雨雪-\\\"-complete"),Map.of(),List.of(resource.stableKey()));
        RenderContractRuntime.endPass(pass);RenderContractRuntime.endFrame(7);
        var point=new CapturePoint(7,"synthetic/compressed",CapturePointKind.AFTER_PASS,-1);
        RenderContractRuntime.requestReadbacks(point,List.of(new RenderContractRuntime.ReadbackRequest("color0",1,"texture-1",
                "RGBA8_UNORM",4,1,1,1,0,1,3,AttachmentSemantic.COLOR)),List.of());
        RenderContractRuntime.recordReadback(point,"color0",1,"texture-1","RGBA8_UNORM",4,1,1,1,0,1,3,new byte[]{1,2,3,(byte)255},List.of());
        RenderContractRuntime.close();assertTrue(RenderContractRuntime.snapshot().ready());assertEquals("passed",RenderContractRuntime.snapshot().status());
        var loaded=RenderContractEvidenceLoader.load(root.resolve("render-contract"));
        assertTrue(loaded.complete(),loaded.incompleteReason());assertEquals(1,loaded.passes().size());assertEquals(1,loaded.captures().size());
        assertEquals("雨雪-\\\"-complete",loaded.passes().getFirst().producers().getFirst().parameters().get("payload"));
        assertEquals(2,index(root.resolve("render-contract")).get("schemaVersion").getAsInt());
    }
    @Test void indexAndPayloadMustFitTogetherRatherThanEachUsingTheWholeFileBudget() throws Exception {
        Path normal=root.resolve("normal");var good=new RenderTraceRecorder(normal,"fixture","a".repeat(40),4,8,16);good.close();
        long index=Files.size(normal.resolve("pass-manifest.json")),payload=Files.size(normal.resolve("pass-manifest.payload.json.gz"));
        long limit=Math.max(index,payload)+32;assertTrue(limit<index+payload);
        System.setProperty("metallum.renderContract.maxManifestBytes",Long.toString(limit));
        Path tight=root.resolve("tight");var rejected=new RenderTraceRecorder(tight,"fixture","a".repeat(40),4,8,16);rejected.close();
        assertFalse(rejected.manifestComplete());assertFalse(rejected.manifestFinalized());
        assertEquals("render contract manifest byte budget exceeded",index(tight).get("manifestFailureReason").getAsString());
    }
    @Test void sharedStorageAndRawInflationLimitsRemainFailClosed() throws Exception {
        Path shared=root.resolve("shared");var budget=ValidationStorageBudget.shared(shared,32);
        var rejected=new RenderTraceRecorder(shared,"fixture","source",4,8,16,budget);rejected.close();
        assertFalse(rejected.manifestComplete());assertTrue(budget.exceeded());
        System.setProperty("metallum.renderContract.maxUncompressedManifestBytes","4096");
        Path raw=root.resolve("raw");var limited=new RenderTraceRecorder(raw,"fixture","source",4,8,16);
        limited.beginFrame(1);long pass=limited.beginPass("synthetic/raw-limit",PassType.RENDER,List.of(),null,null,
                new ViewportRecord(0,0,1,1),ScissorRecord.disabled(),"pipeline",List.of(),Map.of("payload","x".repeat(8192)));
        limited.endPass(pass);limited.close();assertFalse(limited.manifestComplete());
        assertEquals("render contract uncompressed manifest byte limit exceeded",index(raw).get("manifestFailureReason").getAsString());
    }
    @Test void aFailedIndexPublicationCannotBeHiddenByAValidCompressedPayload() throws Exception {
        var recorder=new RenderTraceRecorder(root,"fixture","source",4,8,16);
        Files.delete(root.resolve("pass-manifest.json"));Files.createDirectory(root.resolve("pass-manifest.json"));
        assertThrows(IllegalStateException.class,recorder::close);assertFalse(recorder.manifestComplete());assertFalse(recorder.manifestFinalized());
        assertThrows(Exception.class,()->RenderContractEvidenceLoader.load(root));
    }
}
