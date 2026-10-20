package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Necessary source-draw linkage proof; never a per-pixel visibility or shader-semantic proof. */
public final class MetalMotionFrameManifest {
    enum Event { ACTUAL, HOOKED, OWNED, EXACT, DISOCCLUSION_ONLY, INDIRECT }
    public record Draws(boolean writesColor, long actualCalls, long hookedCalls, long ownedCalls,
                        long exactCalls, long disocclusionOnlyCalls, long indirectCalls) {}
    public record Snapshot(long frameId,long epoch,boolean collected,boolean overflow,
                           Map<String,Draws> programs,List<String> rejectedReasons) {
        public boolean linkageComplete() {return collected && !overflow && !programs.isEmpty() && rejectedReasons.isEmpty();}
        boolean matches(long expectedFrame,long expectedEpoch) {
            return frameId==expectedFrame && epoch==expectedEpoch && linkageComplete();
        }
    }
    private static final int MAX_PROGRAMS=1024;
    private final Map<String,long[]> programs=new TreeMap<>();
    private long frameId,epoch;
    private boolean collected,overflow;

    void reset() {programs.clear();frameId=epoch=0;collected=overflow=false;}
    void begin(long frame,long generation) {
        if(frame<=0 || generation<=0)throw new IllegalArgumentException("Invalid source stamp");
        if(collected)throw new IllegalStateException("Source manifest already bound");
        frameId=frame;epoch=generation;collected=true;
    }
    boolean collecting() {return collected;}
    void record(String program,boolean writesColor,Event event,long calls) {
        if(!collected || calls<=0)return;
        if(program==null || program.isBlank()) {overflow=true;return;}
        long[] counts=programs.get(program);
        if(counts==null) {
            if(programs.size()>=MAX_PROGRAMS) {overflow=true;return;}
            counts=new long[7];counts[6]=writesColor ? 1 : 0;programs.put(program,counts);
        } else if((counts[6]!=0)!=writesColor) {overflow=true;}
        counts[event.ordinal()]=Math.addExact(counts[event.ordinal()],calls);
    }
    Snapshot snapshot() {
        Map<String,Draws> copy=new TreeMap<>();List<String> rejected=new ArrayList<>();
        if(!collected)rejected.add("source-frame-stamp-unavailable");
        if(overflow)rejected.add("source-program-inventory-overflow-or-conflict");
        programs.forEach((key,c)-> {
            copy.put(key,new Draws(c[6]!=0,c[0],c[1],c[2],c[3],c[4],c[5]));
            if(c[6]==0)return;
            if(c[0]==0 || c[0]!=c[1])rejected.add(key+":unlinked-source-draw");
            if(c[1]!=c[2])rejected.add(key+":unowned-source-draw");
            if(c[2]!=c[3])rejected.add(key+":exact-motion-not-encoded");
            if(c[4]!=0)rejected.add(key+":disocclusion-only");
            if(c[5]!=0)rejected.add(key+":indirect-command-correspondence-unproven");
        });
        if(collected && programs.values().stream().noneMatch(c->c[6]!=0 && c[0]>0))
            rejected.add("source-color-program-inventory-empty");
        return new Snapshot(frameId,epoch,collected,overflow,Map.copyOf(copy),List.copyOf(rejected));
    }
}
