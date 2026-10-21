package com.metallum.client.metal.render;

import java.util.Map;
import java.util.TreeMap;

/** Content invariant for a bounded full clear; never a certificate for arbitrary UI shaders. */
final class MetalUiContentProof {
    record Snapshot(long clearProofs,long renderDraws,long potentialShaderWrites,
                    Map<String,Long> invalidations,boolean premultipliedClearOnlyProven,String reason) {}
    private final Map<String,Long> invalidations=new TreeMap<>();
    private long clears,draws,shaderWrites;
    private boolean clearOnly;
    private String reason="no-bounded-full-clear";
    void reset() {invalidations.clear();clears=draws=shaderWrites=0;clearOnly=false;reason="no-bounded-full-clear";}
    void invalidate(String cause) {
        invalidations.merge(cause,1L,Math::addExact);clearOnly=false;reason=cause;
    }
    void render(long calls) {draws=Math.addExact(draws,calls);invalidate("ui-shader-output-range-unproven");}
    void potentialShaderWrite() {shaderWrites++;invalidate("storage-image-write-access-unproven");}
    void clear(float r,float g,float b,float a,boolean full,boolean normalizedStorage) {
        clears++;
        if(!full) {invalidate("partial-clear-does-not-prove-outside-content");return;}
        if(!normalizedStorage || !unit(r) || !unit(g) || !unit(b) || !unit(a)
                || r>a || g>a || b>a) {invalidate("clear-is-not-bounded-premultiplied");return;}
        clearOnly=true;reason="bounded-premultiplied-full-clear";
    }
    Snapshot snapshot() {return new Snapshot(clears,draws,shaderWrites,Map.copyOf(invalidations),clearOnly,reason);}
    private static boolean unit(float value) {return Float.isFinite(value) && value>=0 && value<=1;}
}
