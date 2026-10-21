package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalUiContentProofTest {
    @Test void onlyACompleteBoundedClearCanEstablishTheInvariant() {
        var proof=new MetalUiContentProof();assertFalse(proof.snapshot().premultipliedClearOnlyProven());
        proof.clear(0,0,0,0,true,true);assertTrue(proof.snapshot().premultipliedClearOnlyProven());
        proof.clear(.1F,.2F,.3F,.5F,true,true);assertTrue(proof.snapshot().premultipliedClearOnlyProven());
        for(float[] invalid:new float[][]{{.6F,0,0,.5F},{0,0,0,Float.NaN},{-1,0,0,1},{0,0,0,2}}) {
            proof.clear(invalid[0],invalid[1],invalid[2],invalid[3],true,true);
            assertFalse(proof.snapshot().premultipliedClearOnlyProven());
        }
        proof.clear(0,0,0,0,false,true);assertFalse(proof.snapshot().premultipliedClearOnlyProven());
        proof.clear(0,0,0,0,true,false);assertFalse(proof.snapshot().premultipliedClearOnlyProven());
    }
    @Test void everyLaterWriterInvalidatesAndAFullClearErasesOldContent() {
        var proof=new MetalUiContentProof();proof.clear(0,0,0,0,true,true);
        proof.render(2);assertFalse(proof.snapshot().premultipliedClearOnlyProven());assertEquals(2,proof.snapshot().renderDraws());
        proof.clear(0,0,0,0,true,true);assertTrue(proof.snapshot().premultipliedClearOnlyProven());
        proof.potentialShaderWrite();assertFalse(proof.snapshot().premultipliedClearOnlyProven());
        proof.clear(0,0,0,0,true,true);proof.invalidate("native-or-transfer-write");
        assertFalse(proof.snapshot().premultipliedClearOnlyProven());assertEquals(1,proof.snapshot().potentialShaderWrites());
        assertEquals(1L,proof.snapshot().invalidations().get("native-or-transfer-write"));
        proof.reset();assertEquals(0,proof.snapshot().clearProofs());assertTrue(proof.snapshot().invalidations().isEmpty());
    }
}
