package com.metallum.client.metal.render;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalShaderLibraryProofTest {
    private static final MetalCanonicalShaderReference REFERENCE=MetalCanonicalShaderReference.CURRENT;
    private static final String VERTEX="VERTEX:minecraft:core/entity",FRAGMENT="FRAGMENT:minecraft:core/entity";

    @Test void libraryBaselineCoversInactiveIncludesWithoutPretendingTheyWereCompileReads() {
        var owner=new MetalShaderLibraryProof(REFERENCE,baseline(),null);
        var snapshot=rawEntity();var match=owner.verify(snapshot,Set.of(VERTEX,FRAGMENT));
        assertTrue(owner.canonicalOpen());assertTrue(match.canonicalInputsMatch());
        assertEquals(0,match.includeInputs());assertTrue(snapshot.includes().isEmpty());
        assertFalse(REFERENCE.verify(snapshot,Set.of(VERTEX,FRAGMENT)).canonicalInputsMatch());
    }

    @Test void actualChangedReadCannotBorrowTheUnchangedLibraryBaseline() {
        var owner=new MetalShaderLibraryProof(REFERENCE,baseline(),null);var raw=rawEntity();
        var changed=new MetalShaderInputCapture.Snapshot("fixture",raw.shaders(),
                Map.of("minecraft:fog.glsl",new MetalShaderInputCapture.Input(1610,"0".repeat(64))),true,false,"fixture");
        assertTrue(owner.canonicalOpen());assertFalse(owner.verify(changed,Set.of(VERTEX,FRAGMENT)).canonicalInputsMatch());
        owner.veto("changed-actual-include");owner.veto("later-reason");
        assertEquals("changed-actual-include",owner.reason());assertFalse(owner.canonicalOpen());
    }

    @Test void reloadUsesAnotherOwnerAndClosingOldLibraryCannotAdmitOldPendingProof() {
        var earlier=new MetalShaderLibraryProof(REFERENCE,baseline(),null);
        var newer=new MetalShaderLibraryProof(REFERENCE,baseline(),null);
        assertNotEquals(earlier.generation(),newer.generation());
        assertTrue(earlier.close());assertFalse(earlier.close());
        assertFalse(earlier.verify(rawEntity(),Set.of(VERTEX,FRAGMENT)).canonicalInputsMatch());
        assertEquals("shader-library-owner-closed",earlier.reason());
        assertTrue(newer.verify(rawEntity(),Set.of(VERTEX,FRAGMENT)).canonicalInputsMatch());
        assertFalse(rawEntity().sourceAdmissionGranted());
    }

    @Test void missingAndReplacedLibraryContentStayUnprovenEvenWhenOnePipelineMatches() {
        var replaced=baseline();replaced.put("assets/minecraft/shaders/include/fog.glsl",new MetalShaderInputCapture.Input(1610,"1".repeat(64)));
        var modified=new MetalShaderLibraryProof(REFERENCE,replaced,null);
        assertFalse(modified.canonicalOpen());assertFalse(modified.verify(rawEntity(),Set.of(VERTEX,FRAGMENT)).canonicalInputsMatch());
        var missing=new MetalShaderLibraryProof(REFERENCE,Map.of(),"provider-read-failed");
        assertFalse(missing.canonicalOpen());assertEquals("provider-read-failed",missing.reason());
    }

    private static TreeMap<String,MetalShaderInputCapture.Input> baseline() {
        var values=new TreeMap<String,MetalShaderInputCapture.Input>();REFERENCE.assets().forEach((path,asset)->values.put(path,asset.raw()));return values;
    }
    private static MetalShaderInputCapture.Snapshot rawEntity() {
        // These are verifier fixtures, not GPU execution claims.
        var values=Map.of(VERTEX,REFERENCE.assets().get("assets/minecraft/shaders/core/entity.vsh").raw(),
                FRAGMENT,REFERENCE.assets().get("assets/minecraft/shaders/core/entity.fsh").raw());
        return new MetalShaderInputCapture.Snapshot("fixture",values,Map.of(),true,false,"fixture");
    }
}
