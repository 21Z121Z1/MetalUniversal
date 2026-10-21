package com.metallum.client.metal.render;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/** One immutable provider generation. Actual compile reads must still match its reference. */
final class MetalShaderLibraryProof {
    private static final AtomicLong GENERATIONS=new AtomicLong();
    private final long generation=GENERATIONS.updateAndGet(value->value==Long.MAX_VALUE ? 1 : value+1);
    private final MetalCanonicalShaderReference reference;
    private final Map<String,MetalShaderInputCapture.Input> includes;
    private final boolean baselineCanonical;
    private volatile String veto;
    private volatile boolean closed;

    MetalShaderLibraryProof(MetalCanonicalShaderReference reference,Map<String,MetalShaderInputCapture.Input> actual,String readFailure) {
        this.reference=reference;var match=reference.verifyLibrary(actual);
        baselineCanonical=readFailure==null && match.canonicalInputsMatch();
        veto=readFailure!=null ? readFailure : match.canonicalInputsMatch() ? null : match.reason();
        var includes=new TreeMap<String,MetalShaderInputCapture.Input>();
        actual.forEach((path,input)->{
            String prefix="assets/minecraft/shaders/include/";
            if(path.startsWith(prefix))includes.put("minecraft:"+path.substring(prefix.length()),input);
        });
        this.includes=Map.copyOf(includes);
    }
    long generation() {return generation;}
    boolean canonicalOpen() {return !closed && baselineCanonical && veto==null;}
    boolean closed() {return closed;}
    String reason() {return closed ? "shader-library-owner-closed" : veto==null ? "canonical-shader-library" : veto;}
    synchronized void veto(String reason) {if(veto==null)veto=reason;}
    synchronized boolean close() {if(closed)return false;closed=true;return true;}

    MetalCanonicalShaderReference.Match verify(MetalShaderInputCapture.Snapshot inputs,Set<String> expected) {
        if(!canonicalOpen())return new MetalCanonicalShaderReference.Match(false,reason(),
                inputs==null ? 0 : inputs.shaders().size(),inputs==null ? 0 : inputs.includes().size());
        return reference.verify(inputs,expected,includes);
    }
}
