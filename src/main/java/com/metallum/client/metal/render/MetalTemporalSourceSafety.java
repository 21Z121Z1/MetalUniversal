package com.metallum.client.metal.render;

/** Frame-local draw linkage, limited to ordinary opaque particles. Not a pixel visibility certificate. */
public final class MetalTemporalSourceSafety {
    public record Snapshot(long frameId,long epoch,long actual,long hooked,long owned,long missingCapture,
                    long duplicateColor,long indirect,boolean overflow) {
        boolean admits(long frame,long generation) {
            return frameId>0 && epoch>0 && frameId==frame && epoch==generation && !overflow
                    && actual==hooked && hooked==owned && missingCapture==0 && duplicateColor==0 && indirect==0;
        }
    }
    private long frameId,epoch,actual,hooked,owned,missingCapture,duplicateColor,indirect;
    private boolean overflow;

    void reset() {frameId=epoch=actual=hooked=owned=missingCapture=duplicateColor=indirect=0;overflow=false;}
    void begin(long frame,long generation) {
        if(frame<=0 || generation<=0 || frameId!=0)throw new IllegalStateException("Invalid or repeated Temporal source binding");
        frameId=frame;epoch=generation;
    }
    private long add(long old,long count) {
        if(count<0 || Long.MAX_VALUE-old<count) {overflow=true;return Long.MAX_VALUE;}
        return old+count;
    }
    void actual(long calls,boolean indirectDraw) {
        actual=add(actual,calls);if(indirectDraw)indirect=add(indirect,calls);
    }
    void hook(boolean hasOwner,String cause) {
        hooked=add(hooked,1);
        if(hasOwner)owned=add(owned,1);
        else if("duplicate-color".equals(cause))duplicateColor=add(duplicateColor,1);
        else missingCapture=add(missingCapture,1);
    }
    Snapshot snapshot() {return new Snapshot(frameId,epoch,actual,hooked,owned,missingCapture,duplicateColor,indirect,overflow);}
}
