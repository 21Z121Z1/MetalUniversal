package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Test-source-only experiment. Tokens are raster inputs, never evidence of which old pixel won. */
final class MetalParticlePixelOwnershipPrototype {
    static final int MAX_QUADS=2, IDENTITY_BYTES=16, PAIR_BYTES_PER_PIXEL=40;
    private static final Pattern MAIN=Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)");
    private MetalParticlePixelOwnershipPrototype() {}

    // Readback evidence is bounded by the diagnostic texture size, not the production
    // shader-source input limit. Digest the existing array without a second image copy.
    static Map<String,Object> pixelFingerprint(byte[] pixels) {
        if(pixels.length>3840L*2160*16)throw new IllegalArgumentException("diagnostic-pixel-byte-limit");
        try {return Map.of("bytes",pixels.length,"sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pixels)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }

    static String instrument(String original,ShaderType type) {
        var matcher=MAIN.matcher(original);
        if(!matcher.find())throw new IllegalArgumentException("Missing original main");
        int start=matcher.start(),end=matcher.end();
        if(matcher.find())throw new IllegalArgumentException("Ambiguous original main");
        String preserved=original.substring(0,start)+"void metallumOriginalMain()"+original.substring(end);
        return preserved+(type==ShaderType.VERTEX ? """

                // Diagnostic identity follows the exact uploaded primitive's vertex indices.
                layout(std140) uniform ProofIdentity { uvec4 proofIds[8]; };
                layout(location=15) flat out uvec4 proofElement;
                void main() { metallumOriginalMain(); proofElement=proofIds[gl_VertexIndex]; }
                """ : """

                layout(location=15) flat in uvec4 proofElement;
                layout(location=1) out uvec4 proofOwner;
                // A discard in the unchanged original main discards BOTH outputs.
                void main() { metallumOriginalMain(); proofOwner=proofElement; }
                """);
    }

    /** Collision/lifetime ledger for controlled emission inputs, not a CPU visibility map. */
    static final class Registry {
        private final Map<Long,Handle> live=new HashMap<>();
        private final Set<Long> poisoned=new HashSet<>();
        private long epoch=1;
        static final class Handle {
            final Object emitter;final long id,generation,epoch;
            private Handle(Object emitter,long id,long generation,long epoch) {this.emitter=emitter;this.id=id;this.generation=generation;this.epoch=epoch;}
        }
        Handle capture(Object emitter,long stableEmissionId,long generation) {
            if(emitter==null || stableEmissionId<=0 || generation<=0 || poisoned.contains(stableEmissionId))return null;
            var previous=live.get(stableEmissionId);
            if(previous!=null && previous.emitter!=emitter) {poisoned.add(stableEmissionId);live.remove(stableEmissionId);return null;}
            var ticket=new Handle(emitter,stableEmissionId,generation,epoch);live.put(stableEmissionId,ticket);return ticket;
        }
        boolean live(Handle h) {return h!=null && h.epoch==epoch && !poisoned.contains(h.id) && live.get(h.id)==h;}
        void reset() {epoch++;live.clear();poisoned.clear();}
        ByteBuffer freeze(List<Handle> emissions) {
            if(emissions.isEmpty() || emissions.size()>MAX_QUADS)throw new IllegalArgumentException("Unproven diagnostic batch range");
            ByteBuffer data=ByteBuffer.allocateDirect(MAX_QUADS*4*IDENTITY_BYTES).order(ByteOrder.nativeOrder());
            for(int q=0;q<emissions.size();q++) {
                var h=emissions.get(q);boolean known=live(h);
                for(int v=0;v<4;v++) {int p=(q*4+v)*IDENTITY_BYTES;
                    data.putInt(p,known?(int)h.id:0).putInt(p+4,known?(int)(h.id>>>32):0)
                        .putInt(p+8,known?(int)h.generation:0).putInt(p+12,known?(int)(h.generation>>>32):0);
                }
            }
            return data;
        }
    }

    /** Two real resource slots; submit/completion/parity are separate states. No early swap. */
    static final class History<S extends AutoCloseable> implements AutoCloseable {
        enum State {OPEN,SUBMITTED,COMPLETE}
        static final class Frame<S> {
            final long serial,epoch;final int slot;final S current,previous;State state=State.OPEN;boolean denied;
            private Frame(long serial,long epoch,int slot,S current,S previous) {this.serial=serial;this.epoch=epoch;this.slot=slot;this.current=current;this.previous=previous;}
        }
        private final List<S> slots=new ArrayList<>();
        private int width,height,accepted=-1;private String source="";private long serial,epoch;
        private Frame<S> active;private boolean closed;
        Frame<S> begin(int w,int h,String sourceStamp,Supplier<S> factory) {
            if(closed || active!=null)throw new IllegalStateException("Closed or unretired ownership transaction");
            bytes(w,h);Objects.requireNonNull(sourceStamp);
            if(slots.isEmpty() || w!=width || h!=height || !source.equals(sourceStamp)) {
                invalidate();
                try {slots.add(factory.get());slots.add(factory.get());}
                catch(RuntimeException|Error failure) {release();throw failure;}
                width=w;height=h;source=sourceStamp;
            }
            int next=accepted==0?1:0;
            active=new Frame<>(++serial,epoch,next,slots.get(next),accepted<0?null:slots.get(accepted));return active;
        }
        void submitted(Frame<S> f) {require(f,State.OPEN);f.state=State.SUBMITTED;}
        void completed(Frame<S> f,boolean success) {
            if(active!=f || f.epoch!=epoch)return;
            if(!success && f.state==State.OPEN){active=null;return;}
            require(f,State.SUBMITTED);
            if(!success || f.denied)active=null;else f.state=State.COMPLETE;
        }
        boolean accept(Frame<S> f,boolean exactSourceAndPixelParity) {
            if(active!=f || f.epoch!=epoch)return false;
            require(f,State.COMPLETE);
            if(exactSourceAndPixelParity && !f.denied)accepted=f.slot;
            active=null;return exactSourceAndPixelParity && !f.denied;
        }
        void discard(Frame<S> f) {
            if(active!=f)return;
            if(f.state==State.SUBMITTED)f.denied=true;else active=null;
        }
        int acceptedSlot() {return accepted;}
        long epoch() {return epoch;}
        void invalidate() {
            if(active!=null && active.state==State.SUBMITTED)throw new IllegalStateException("GPU work must retire before invalidation");
            active=null;accepted=-1;epoch++;release();
        }
        private void require(Frame<S> f,State state) {
            if(active!=f || f.epoch!=epoch || f.state!=state)throw new IllegalStateException("Stale or unordered ownership completion");
        }
        private void release() {
            RuntimeException failure=null;
            for(S slot:slots)try {slot.close();}catch(Exception e) {if(failure==null)failure=new IllegalStateException(e);else failure.addSuppressed(e);}
            slots.clear();if(failure!=null)throw failure;
        }
        @Override public void close() {invalidate();closed=true;}
    }
    static long bytes(int width,int height) {
        if(width<=0 || height<=0 || width>3840 || height>2160)throw new IllegalArgumentException("Diagnostic resolution budget");
        return Math.multiplyExact(Math.multiplyExact((long)width,height),PAIR_BYTES_PER_PIXEL);
    }

    static final String RESOLVE_VERTEX="""
            #version 330
            void main() {
                vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                gl_Position=vec4(p*2.0-1.0,0,1);
            }
            """;
    static final String RESOLVE_FRAGMENT="""
            #version 330
            uniform usampler2D OldOwner;
            uniform sampler2D OldDepth;
            uniform usampler2D CurrentOwner;
            uniform sampler2D CurrentDepth;
            uniform sampler2D SourceMotion;
            uniform sampler2D SourcePair;
            uniform sampler2D SourceValid;
            layout(std140) uniform ProofState { ivec4 proofState; };
            layout(location=0) out float accepted;
            void main() {
                ivec2 p=ivec2(gl_FragCoord.xy), size=textureSize(CurrentOwner,0);
                vec2 xy=texelFetch(SourceMotion,p,0).xy;
                vec2 oldPixel=vec2(p)+vec2(0.5)+xy*vec2(size)*0.5;
                accepted=0.0;
                if(proofState.x!=1 || any(isnan(oldPixel)) || any(isinf(oldPixel)))return;
                ivec2 q=ivec2(floor(oldPixel));
                if(any(lessThan(q,ivec2(0))) || any(greaterThanEqual(q,size)))return;
                uvec4 current=texelFetch(CurrentOwner,p,0), old=texelFetch(OldOwner,q,0);
                vec2 z=texelFetch(SourcePair,p,0).xy;
                if(texelFetch(SourceValid,p,0).r<0.999 || all(equal(current.xy,uvec2(0))) || all(equal(current.zw,uvec2(0))))return;
                if(any(notEqual(current,old)) || any(isnan(z)) || any(isinf(z)) || z.x<0.0)return;
                float previousDepth=texelFetch(OldDepth,q,0).r,currentDepth=texelFetch(CurrentDepth,p,0).r;
                if(abs(previousDepth-z.x)>0.000003 || abs(currentDepth-z.y)>0.000003)return;
                accepted=1.0;
            }
            """;
}
