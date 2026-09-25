package com.metallum.client.validation.expectation;

import com.metallum.client.validation.capture.CapturedResource;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Byte/texel exact expectation with an optional byte mask. */
public final class ExactExpectation implements Expectation {
    private final byte[] expected;
    private final byte[] mask;

    public ExactExpectation(final byte[] expected) {
        this(expected, null);
    }

    public ExactExpectation(final byte[] expected, final byte[] mask) {
        if (expected == null || (mask != null && mask.length != expected.length)) {
            throw new IllegalArgumentException("Exact expectation has invalid reference or mask");
        }
        this.expected = expected.clone();
        this.mask = mask == null ? null : mask.clone();
    }

    @Override
    public ExpectationResult evaluate(final CapturedResource actual, final ExpectationContext context) {
        byte[] actualBytes = actual.bytes();
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (actualBytes.length != expected.length) {
            metrics.put("actualBytes", actualBytes.length);
            metrics.put("expectedBytes", expected.length);
            return ExpectationResult.fail("exact", "byte count differs", metrics);
        }
        int mismatchBytes = 0;
        for (int index = 0; index < expected.length; index++) {
            int maskByte = mask == null ? 0xff : mask[index] & 0xff;
            if (((actualBytes[index] ^ expected[index]) & maskByte) != 0) {
                mismatchBytes++;
            }
        }
        int bytesPerTexel = Math.max(1, actual.captureFormat().bytesPerTexel());
        metrics.put("mismatchBytes", mismatchBytes);
        metrics.put("mismatchTexels", (mismatchBytes + bytesPerTexel - 1) / bytesPerTexel);
        return mismatchBytes == 0
                ? ExpectationResult.pass("exact", "all bytes match", metrics)
                : ExpectationResult.fail("exact", "exact bytes differ", metrics);
    }

    @Override
    public byte[] expectedBytes() {
        return expected.clone();
    }

    public byte[] mask() {
        return mask == null ? null : mask.clone();
    }
}
