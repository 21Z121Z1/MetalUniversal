package com.metallum.client.validation.expectation;

import com.metallum.client.validation.capture.CapturedResource;
import com.metallum.client.validation.contract.CaptureFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Numeric expectation for float, integer, depth and HDR attachments. */
public final class NumericExpectation implements Expectation {
    public enum NaNPolicy { FAIL, IGNORE }

    public enum InfPolicy { FAIL, ALLOW }

    private final double[] expected;
    private final double absoluteTolerance;
    private final double relativeTolerance;
    private final int ulpTolerance;
    private final Double minimum;
    private final Double maximum;
    private final NaNPolicy nanPolicy;
    private final InfPolicy infPolicy;

    public NumericExpectation(
            final double[] expected,
            final double absoluteTolerance,
            final double relativeTolerance
    ) {
        this(expected, absoluteTolerance, relativeTolerance, 0, null, null,
                NaNPolicy.FAIL, InfPolicy.FAIL);
    }

    public NumericExpectation(
            final Double minimum,
            final Double maximum,
            final double absoluteTolerance,
            final double relativeTolerance
    ) {
        this(null, absoluteTolerance, relativeTolerance, 0, minimum, maximum,
                NaNPolicy.FAIL, InfPolicy.FAIL);
    }

    public NumericExpectation(
            final double[] expected,
            final double absoluteTolerance,
            final double relativeTolerance,
            final int ulpTolerance,
            final Double minimum,
            final Double maximum,
            final NaNPolicy nanPolicy,
            final InfPolicy infPolicy
    ) {
        if (expected == null && minimum == null && maximum == null) {
            throw new IllegalArgumentException("Numeric expectation needs expected values or bounds");
        }
        if (absoluteTolerance < 0.0 || relativeTolerance < 0.0 || ulpTolerance < 0
                || (minimum != null && maximum != null && minimum > maximum)) {
            throw new IllegalArgumentException("Invalid numeric tolerance or bounds");
        }
        this.expected = expected == null ? null : expected.clone();
        this.absoluteTolerance = absoluteTolerance;
        this.relativeTolerance = relativeTolerance;
        this.ulpTolerance = ulpTolerance;
        this.minimum = minimum;
        this.maximum = maximum;
        this.nanPolicy = nanPolicy == null ? NaNPolicy.FAIL : nanPolicy;
        this.infPolicy = infPolicy == null ? InfPolicy.FAIL : infPolicy;
    }

    @Override
    public ExpectationResult evaluate(final CapturedResource actual, final ExpectationContext context) {
        double[] values = decode(actual);
        List<Double> errors = new ArrayList<>();
        int invalid = 0;
        int outOfBounds = 0;
        int mismatches = 0;
        double maxError = 0.0;
        double sumError = 0.0;
        for (int index = 0; index < values.length; index++) {
            double value = values[index];
            if (Double.isNaN(value)) {
                if (nanPolicy == NaNPolicy.FAIL) {
                    invalid++;
                }
                continue;
            }
            if (Double.isInfinite(value)) {
                if (infPolicy == InfPolicy.FAIL) {
                    invalid++;
                }
                continue;
            }
            if (minimum != null && value < minimum || maximum != null && value > maximum) {
                outOfBounds++;
            }
            if (expected != null) {
                if (index >= expected.length) {
                    mismatches++;
                    continue;
                }
                double reference = expected[index];
                double error = Math.abs(value - reference);
                double allowed = absoluteTolerance + relativeTolerance * Math.abs(reference);
                boolean within = error <= allowed || withinUlps(value, reference, ulpTolerance);
                if (!within) {
                    mismatches++;
                }
                errors.add(error);
                sumError += error;
                maxError = Math.max(maxError, error);
            }
        }
        if (expected != null && expected.length != values.length) {
            mismatches += Math.abs(expected.length - values.length);
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("valueCount", values.length);
        metrics.put("invalidValueCount", invalid);
        metrics.put("outOfBoundsCount", outOfBounds);
        metrics.put("mismatchValueCount", mismatches);
        metrics.put("maxError", maxError);
        metrics.put("meanError", errors.isEmpty() ? 0.0 : sumError / errors.size());
        if (!errors.isEmpty()) {
            Collections.sort(errors);
            metrics.put("p95Error", percentile(errors, 0.95));
            metrics.put("p99Error", percentile(errors, 0.99));
        }
        boolean passed = invalid == 0 && outOfBounds == 0 && mismatches == 0;
        return passed
                ? ExpectationResult.pass("numeric", "numeric contract satisfied", metrics)
                : ExpectationResult.fail("numeric", "numeric contract violated", metrics);
    }

    private double[] decode(final CapturedResource actual) {
        CaptureFormat format = actual.captureFormat();
        int components = format.componentCount();
        int bytesPerComponent = Math.max(1, format.bytesPerTexel() / components);
        byte[] bytes = actual.bytes();
        double[] result = new double[actual.texelCount() * components];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < result.length; index++) {
            result[index] = switch (format.componentType()) {
                case UINT8 -> buffer.get() & 0xff;
                case SINT8 -> buffer.get();
                case UINT16 -> buffer.getShort() & 0xffff;
                case SINT16 -> buffer.getShort();
                case UINT32 -> Integer.toUnsignedLong(buffer.getInt());
                case SINT32 -> buffer.getInt();
                case FLOAT16 -> halfToFloat(buffer.getShort());
                case FLOAT32 -> buffer.getFloat();
                case UNKNOWN -> decodeUnknown(buffer, bytesPerComponent);
            };
            if (format.normalized()) {
                result[index] = normalized(result[index], format.componentType());
            }
        }
        return result;
    }

    private static double decodeUnknown(final ByteBuffer buffer, final int bytesPerComponent) {
        return switch (bytesPerComponent) {
            case 1 -> buffer.get() & 0xff;
            case 2 -> buffer.getShort() & 0xffff;
            case 4 -> buffer.getInt() & 0xffff_ffffL;
            default -> throw new IllegalArgumentException("Unsupported unknown component width " + bytesPerComponent);
        };
    }

    private static double normalized(final double value, final CaptureFormat.ComponentType type) {
        return switch (type) {
            case UINT8 -> value / 255.0;
            case SINT8 -> Math.max(-1.0, value / 127.0);
            case UINT16 -> value / 65535.0;
            case SINT16 -> Math.max(-1.0, value / 32767.0);
            case UINT32 -> value / 4_294_967_295.0;
            case SINT32 -> Math.max(-1.0, value / 2_147_483_647.0);
            default -> value;
        };
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

    private static boolean withinUlps(final double actual, final double expected, final int ulps) {
        if (ulps <= 0 || !Double.isFinite(actual) || !Double.isFinite(expected)) {
            return false;
        }
        long actualBits = Double.doubleToLongBits(actual);
        long expectedBits = Double.doubleToLongBits(expected);
        if (actualBits < 0) actualBits = Long.MIN_VALUE - actualBits;
        if (expectedBits < 0) expectedBits = Long.MIN_VALUE - expectedBits;
        long distance = actualBits >= expectedBits ? actualBits - expectedBits : expectedBits - actualBits;
        return distance <= ulps;
    }

    private static double percentile(final List<Double> sorted, final double percentile) {
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }
}
