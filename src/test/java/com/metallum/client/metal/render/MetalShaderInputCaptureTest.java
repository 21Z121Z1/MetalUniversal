package com.metallum.client.metal.render;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalShaderInputCaptureTest {
    @Test void sameNameReplacementAndChangedIncludeCannotReuseAContentIdentity() {
        var original=new MetalShaderInputCapture();original.shader("VERTEX:minecraft:core/entity","position");
        original.include("minecraft:fog.glsl",utf8("fog"));
        var changedVertex=new MetalShaderInputCapture();changedVertex.shader("VERTEX:minecraft:core/entity","deformed-position");
        changedVertex.include("minecraft:fog.glsl",utf8("fog"));
        var changedInclude=new MetalShaderInputCapture();changedInclude.shader("VERTEX:minecraft:core/entity","position");
        changedInclude.include("minecraft:fog.glsl",utf8("changed-fog"));
        assertNotEquals(original.finish().contentSha256(),changedVertex.finish().contentSha256());
        assertNotEquals(original.finish().contentSha256(),changedInclude.finish().contentSha256());
        assertFalse(original.finish().sourceAdmissionGranted());
    }

    @Test void reversingCompletionKeepsEachCompileOwnImmutableInputs() {
        var earlier=new MetalShaderInputCapture();earlier.shader("VERTEX:same-id","earlier");
        var newer=new MetalShaderInputCapture();newer.shader("VERTEX:same-id","newer");
        var newerResult=newer.finish();var earlierResult=earlier.finish();
        assertNotEquals(newerResult.shaders(),earlierResult.shaders());
        assertSame(newerResult,newer.finish());assertSame(earlierResult,earlier.finish());
        assertThrows(UnsupportedOperationException.class,()->earlierResult.shaders().clear());
        assertThrows(IllegalStateException.class,()->earlier.shader("VERTEX:same-id","after-reload"));
    }

    @Test void includeHashBorrowsTheExactRemainingBytesWithoutChangingOrRetainingTheView() {
        var capture=new MetalShaderInputCapture();capture.shader("VERTEX:source","shader");
        var bytes=utf8("X雾Y");bytes.position(1);bytes.limit(bytes.limit()-1);
        int position=bytes.position(),limit=bytes.limit();capture.include("fog",bytes);
        assertEquals(position,bytes.position());assertEquals(limit,bytes.limit());
        bytes.put(position,(byte)0);
        var expected=new MetalShaderInputCapture();expected.shader("VERTEX:source","shader");expected.include("fog",utf8("雾"));
        assertEquals(expected.finish(),capture.finish());
        assertEquals(3,capture.finish().includes().get("fog").bytes());
    }

    @Test void changingAnInputDuringOneCompileRetainsItsFirstFailure() {
        var capture=new MetalShaderInputCapture();capture.shader("VERTEX:id","first");capture.shader("VERTEX:id","second");
        capture.reject("later-failure");
        assertFalse(capture.finish().consistent());assertFalse(capture.finish().sourceAdmissionGranted());
        assertEquals("shader-input-changed-during-compile",capture.finish().reason());
    }

    @Test void missingOversizedAndExcessEntriesNeverBecomeProofs() {
        var missing=new MetalShaderInputCapture();missing.shader("VERTEX:id",null);
        assertFalse(missing.finish().consistent());
        var large=new MetalShaderInputCapture();large.shader("VERTEX:id","x".repeat(1024*1024+1));
        assertEquals("shader-input-byte-limit",large.finish().reason());assertTrue(large.finish().shaders().isEmpty());
        var entries=new MetalShaderInputCapture();entries.shader("VERTEX:id","shader");
        for(int i=0;i<256;i++)entries.include("include-"+i,utf8("include"));
        assertEquals(255,entries.finish().includes().size());assertFalse(entries.finish().consistent());
        assertEquals("shader-input-capture-budget",entries.finish().reason());
    }

    private static ByteBuffer utf8(String source) {return ByteBuffer.wrap(source.getBytes(StandardCharsets.UTF_8));}
}
