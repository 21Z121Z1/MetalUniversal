package com.metallum.client.metal.render;

/** Blend compatibility is necessary but does not attest a shader's alpha/range or all writes. */
public final class MetalUiBlendContract {
    public record Equation(String source,String destination,String operation) {}
    public record State(boolean writesAllChannels,Equation rgb,Equation alpha) {
        public boolean boundedStraightSourceOver() {
            return writesAllChannels && rgb!=null && alpha!=null
                    && rgb.equals(new Equation("SRC_ALPHA","ONE_MINUS_SRC_ALPHA","ADD"))
                    && alpha.equals(new Equation("ONE","ONE_MINUS_SRC_ALPHA","ADD"));
        }
    }
    private MetalUiBlendContract() {}
}
