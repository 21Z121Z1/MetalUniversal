package com.metallum.client.metal.render;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalCanonicalShaderReferenceTest {
    private static final String VERTEX="VERTEX:minecraft:core/entity",FRAGMENT="FRAGMENT:minecraft:core/entity";
    private static final Set<String> STAGES=Set.of(VERTEX,FRAGMENT);
    private static final MetalCanonicalShaderReference REFERENCE=MetalCanonicalShaderReference.CURRENT;

    @Test void packagedReferenceContainsTheExactClientAuditOutsideResourcePackAssetNamespaces() {
        assertTrue(REFERENCE.ready());assertEquals(80,REFERENCE.assets().size());
        assertEquals("e877b6a07acd633fb3bb475002175cec036e7b87",MetalCanonicalShaderReference.CLIENT_SHA1);
        assertTrue(MetalCanonicalShaderReference.RESOURCE.startsWith("/com/metallum/"));
        assertEquals("17d9e367130a9e7350487fb476e2ccf90e0e43dda46361cb29d445c5e8aa2f93",asset("core/entity.vsh").raw().sha256());
    }

    @Test void exactExpandedReferenceNeedsNoBorrowedIncludesAndDoesNotGrantFeatureAdmission() {
        var snapshot=snapshot(Map.of(VERTEX,asset("core/entity.vsh").expanded(),FRAGMENT,asset("core/entity.fsh").expanded()),Map.of());
        assertTrue(REFERENCE.verify(snapshot,STAGES).canonicalInputsMatch());
        assertFalse(snapshot.sourceAdmissionGranted());
        var incomplete=snapshot(Map.of(VERTEX,asset("core/entity.vsh").expanded()),Map.of());
        assertFalse(REFERENCE.verify(incomplete,STAGES).canonicalInputsMatch());
        assertFalse(REFERENCE.verify(null,STAGES).canonicalInputsMatch());
    }

    @Test void rawInputsRequireTheCompleteCanonicalIncludeClosureAndRejectReplacement() {
        var roots=Map.of(VERTEX,asset("core/entity.vsh").raw(),FRAGMENT,asset("core/entity.fsh").raw());
        var includes=new TreeMap<String,MetalShaderInputCapture.Input>();
        addImports(asset("core/entity.vsh"),includes);addImports(asset("core/entity.fsh"),includes);
        assertTrue(REFERENCE.verify(snapshot(roots,includes),STAGES).canonicalInputsMatch());
        var missing=new TreeMap<>(includes);missing.remove("minecraft:fog.glsl");
        assertEquals("canonical-include-content-not-observed",REFERENCE.verify(snapshot(roots,missing),STAGES).reason());
        var replaced=new TreeMap<>(includes);replaced.put("minecraft:fog.glsl",new MetalShaderInputCapture.Input(1610,"0".repeat(64)));
        assertEquals("include-content-mismatch:minecraft:fog.glsl",REFERENCE.verify(snapshot(roots,replaced),STAGES).reason());
    }

    @Test void sameIdDifferentBytesUnknownNamespacesAndWrongCompileStagesStayRejected() {
        var changed=snapshot(Map.of(VERTEX,new MetalShaderInputCapture.Input(5457,"1".repeat(64)),FRAGMENT,asset("core/entity.fsh").expanded()),Map.of());
        assertEquals("shader-content-mismatch:"+VERTEX,REFERENCE.verify(changed,STAGES).reason());
        var unknown=snapshot(Map.of("VERTEX:custom:core/entity",asset("core/entity.vsh").expanded()),Map.of());
        assertFalse(REFERENCE.verify(unknown,Set.of("VERTEX:custom:core/entity")).canonicalInputsMatch());
        var correct=snapshot(Map.of(VERTEX,asset("core/entity.vsh").expanded(),FRAGMENT,asset("core/entity.fsh").expanded()),Map.of());
        assertFalse(REFERENCE.verify(correct,Set.of("VERTEX:minecraft:core/item",FRAGMENT)).canonicalInputsMatch());
        var inconsistent=new MetalShaderInputCapture.Snapshot("fixture",correct.shaders(),Map.of(),false,false,"changed-during-compile");
        assertFalse(REFERENCE.verify(inconsistent,STAGES).canonicalInputsMatch());
    }

    private static MetalCanonicalShaderReference.Asset asset(String path) {return REFERENCE.assets().get("assets/minecraft/shaders/"+path);}
    private static void addImports(MetalCanonicalShaderReference.Asset source,Map<String,MetalShaderInputCapture.Input> includes) {
        for(String id:source.imports()) {
            var dependency=asset("include/"+id.substring("minecraft:".length()));
            if(includes.putIfAbsent(id,dependency.raw())==null)addImports(dependency,includes);
        }
    }
    private static MetalShaderInputCapture.Snapshot snapshot(Map<String,MetalShaderInputCapture.Input> shaders,
                                                            Map<String,MetalShaderInputCapture.Input> includes) {
        // Fingerprint fixtures exercise the verifier. Actual GLSL compilation is covered by the GPU oracle.
        return new MetalShaderInputCapture.Snapshot("fixture",Map.copyOf(shaders),Map.copyOf(includes),true,false,"fixture");
    }
}
