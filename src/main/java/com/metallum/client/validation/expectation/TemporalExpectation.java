package com.metallum.client.validation.expectation;

import com.metallum.client.validation.capture.CapturedResource;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stateful prefix/sequence expectation for temporal resources. */
public final class TemporalExpectation implements Expectation {
    private final int warmupFrames;
    private final double maximumMeanAbsoluteDelta;
    private final double maximumP95AbsoluteDelta;
    private final boolean requireFinite;
    private CapturedResource previous;
    private int observedFrames;

    public TemporalExpectation(
            final int warmupFrames,
            final double maximumMeanAbsoluteDelta,
            final double maximumP95AbsoluteDelta,
            final boolean requireFinite
    ) {
        if (warmupFrames < 0 || maximumMeanAbsoluteDelta < 0.0
                || maximumP95AbsoluteDelta < 0.0) {
            throw new IllegalArgumentException("Invalid temporal expectation");
        }
        this.warmupFrames = warmupFrames;
        this.maximumMeanAbsoluteDelta = maximumMeanAbsoluteDelta;
        this.maximumP95AbsoluteDelta = maximumP95AbsoluteDelta;
        this.requireFinite = requireFinite;
    }

    public TemporalExpectation(final int warmupFrames, final double maximumMeanAbsoluteDelta) {
        this(warmupFrames, maximumMeanAbsoluteDelta, maximumMeanAbsoluteDelta, true);
    }

    @Override
    public synchronized ExpectationResult evaluate(
            final CapturedResource actual,
            final ExpectationContext context
    ) {
        observedFrames++;
        byte[] current = actual.bytes();
        Map<String, Object> metrics = new LinkedHashMap<>();
        int invalid = finiteInvalidCount(actual);
        metrics.put("observedFrames", observedFrames);
        metrics.put("invalidValueCount", invalid);
        if (previous == null || observedFrames <= warmupFrames) {
            previous = actual.copy();
            return invalid == 0 && requireFinite
                    ? ExpectationResult.pass("temporal", "warmup frame recorded", metrics)
                    : invalid == 0 || !requireFinite
                    ? ExpectationResult.pass("temporal", "warmup frame recorded", metrics)
                    : ExpectationResult.fail("temporal", "warmup contains non-finite values", metrics);
        }
        if (!actual.sameShape(previous)) {
            metrics.put("previousShape", previous.toString());
            previous = actual.copy();
            return ExpectationResult.fail("temporal", "temporal resource shape changed", metrics);
        }
        byte[] prior = previous.bytes();
        double[] errors = new double[current.length];
        double sum = 0.0;
        double max = 0.0;
        for (int index = 0; index < current.length; index++) {
            double error = Math.abs((current[index] & 0xff) - (prior[index] & 0xff));
            errors[index] = error;
            sum += error;
            max = Math.max(max, error);
        }
        java.util.Arrays.sort(errors);
        double mean = errors.length == 0 ? 0.0 : sum / errors.length;
        double p95 = errors.length == 0 ? 0.0 : errors[Math.min(errors.length - 1,
                (int) Math.ceil(errors.length * 0.95) - 1)];
        metrics.put("meanAbsoluteByteDelta", mean);
        metrics.put("p95AbsoluteByteDelta", p95);
        metrics.put("maxAbsoluteByteDelta", max);
        previous = actual.copy();
        boolean passed = (!requireFinite || invalid == 0)
                && mean <= maximumMeanAbsoluteDelta
                && p95 <= maximumP95AbsoluteDelta;
        return passed
                ? ExpectationResult.pass("temporal", "temporal contract satisfied", metrics)
                : ExpectationResult.fail("temporal", "temporal instability exceeds contract", metrics);
    }

    public synchronized void reset() {
        previous = null;
        observedFrames = 0;
    }

    private static int finiteInvalidCount(final CapturedResource actual) {
        if (!actual.captureFormat().componentType().name().startsWith("FLOAT")) {
            return 0;
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(actual.bytes())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int components = actual.captureFormat().componentCount();
        int invalid = 0;
        for (int i = 0; i < actual.texelCount() * components; i++) {
            double value = actual.captureFormat().componentType()
                    == com.metallum.client.validation.contract.CaptureFormat.ComponentType.FLOAT16
                    ? halfToFloat(buffer.getShort()) : buffer.getFloat();
            if (!Double.isFinite(value)) invalid++;
        }
        return invalid;
    }

    private static float halfToFloat(final short bits) {
        int value = bits & 0xffff;
        int sign = (value >>> 15) & 1;
        int exponent = (value >>> 10) & 0x1f;
        int fraction = value & 0x3ff;
        if (exponent == 0) {
            if (fraction == 0) return sign == 0 ? 0.0f : -0.0f;
            return (float) ((sign == 0 ? 1.0 : -1.0) * Math.scalb(fraction, -24));
        }
        if (exponent == 0x1f) {
            return fraction == 0
                    ? (sign == 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY)
                    : Float.NaN;
        }
        return (float) ((sign == 0 ? 1.0 : -1.0) * Math.scalb(1024.0 + fraction, exponent - 25));
    }
}
