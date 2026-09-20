package com.metallum.client.metal.render;

/** Current-process Mach memory sample; lifetime peak is never a window maximum. */
public final class NativeProcessMemory {
    public static final int WORDS = 6;
    // TASK_VM_INFO revision 1 includes phys_footprint through word 38.
    public static final int MIN_TASK_INFO_WORDS = 38;

    private NativeProcessMemory() { }

    public static Sample decode(long[] words) {
        if (words.length != WORDS || words[0] != 1) {
            throw new IllegalArgumentException("Invalid process-memory ABI shape");
        }
        return new Sample(words[1], words[2], words[3], words[4], words[5]);
    }

    public record Sample(long kernelStatus, long returnedWordCount, long residentBytes,
                         long physicalFootprintBytes, long lifetimeResidentPeakBytes) {
        public boolean successful() {
            return kernelStatus == 0 && returnedWordCount >= MIN_TASK_INFO_WORDS
                    && residentBytes > 0 && physicalFootprintBytes >= 0 && lifetimeResidentPeakBytes >= 0;
        }
    }
}
