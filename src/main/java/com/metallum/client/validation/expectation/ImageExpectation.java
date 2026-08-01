package com.metallum.client.validation.expectation;

import com.metallum.client.validation.capture.CapturedResource;

import java.util.LinkedHashMap;
import java.util.Map;

/** LDR image comparison. It is intentionally separate from numeric attachment checks. */
public final class ImageExpectation implements Expectation {
    private final byte[] expected;
    private final int channelCount;
    private final int perChannelTolerance;
    private final boolean ignoreAlpha;
    private final ImageNormalization actualNormalization;
    private final ImageNormalization expectedNormalization;

    public ImageExpectation(
            final byte[] expected,
            final int channelCount,
            final int perChannelTolerance,
            final boolean ignoreAlpha
    ) {
        this(
                expected,
                channelCount,
                perChannelTolerance,
                ignoreAlpha,
                ImageNormalization.raw(channelCount),
                ImageNormalization.raw(channelCount)
        );
    }

    /** Creates an image expectation with explicit actual and expected encodings. */
    public ImageExpectation(
            final byte[] expected,
            final int channelCount,
            final int perChannelTolerance,
            final boolean ignoreAlpha,
            final ImageNormalization actualNormalization,
            final ImageNormalization expectedNormalization
    ) {
        if (expected == null || (channelCount != 3 && channelCount != 4)
                || perChannelTolerance < 0 || perChannelTolerance > 255
                || actualNormalization == null || expectedNormalization == null) {
            throw new IllegalArgumentException("Invalid image expectation");
        }
        if (ignoreAlpha && channelCount != 4) {
            throw new IllegalArgumentException("ignoreAlpha requires four channels");
        }
        if (!actualNormalization.isRaw() && !actualNormalization.isFullySpecified(channelCount)) {
            throw new IllegalArgumentException("Actual image normalization is incomplete");
        }
        if (!expectedNormalization.isRaw() && !expectedNormalization.isFullySpecified(channelCount)) {
            throw new IllegalArgumentException("Expected image normalization is incomplete");
        }
        this.expected = expected.clone();
        this.channelCount = channelCount;
        this.perChannelTolerance = perChannelTolerance;
        this.ignoreAlpha = ignoreAlpha;
        this.actualNormalization = actualNormalization;
        this.expectedNormalization = expectedNormalization;
    }

    public ImageExpectation(final byte[] expected, final int perChannelTolerance) {
        this(expected, 4, perChannelTolerance, false);
    }

    @Override
    public ExpectationResult evaluate(final CapturedResource actual, final ExpectationContext context) {
        byte[] bytes = actual.bytes();
        if (bytes.length != expected.length) {
            return ExpectationResult.fail(
                    "image",
                    "image byte count differs",
                    Map.of("actualBytes", bytes.length, "expectedBytes", expected.length)
            );
        }
        if (actual.captureFormat().bytesPerTexel() != channelCount) {
            return ExpectationResult.fail(
                    "image",
                    "image channel count does not match capture format",
                    Map.of(
                            "channelCount", channelCount,
                            "captureBytesPerTexel", actual.captureFormat().bytesPerTexel()
                    )
            );
        }
        if (actualNormalization.isRaw() || expectedNormalization.isRaw()) {
            return evaluateRaw(bytes);
        }
        return evaluateNormalized(actual);
    }

    private ExpectationResult evaluateRaw(final byte[] bytes) {
        int compared = 0;
        int mismatchPixels = 0;
        long squaredError = 0L;
        int maxError = 0;
        for (int offset = 0; offset < bytes.length; offset += channelCount) {
            boolean mismatch = false;
            for (int channel = 0; channel < channelCount; channel++) {
                if (ignoreAlpha && channel == 3) continue;
                int error = Math.abs((bytes[offset + channel] & 0xff) - (expected[offset + channel] & 0xff));
                squaredError += (long) error * error;
                maxError = Math.max(maxError, error);
                compared++;
                mismatch |= error > perChannelTolerance;
            }
            if (mismatch) mismatchPixels++;
        }
        double rmse = compared == 0 ? 0.0 : Math.sqrt((double) squaredError / compared);
        double mse = compared == 0 ? 0.0 : (double) squaredError / compared;
        Object psnr = mse == 0.0 ? "infinite" : 20.0 * Math.log10(255.0 / Math.sqrt(mse));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("mismatchPixels", mismatchPixels);
        metrics.put("comparedChannels", compared);
        metrics.put("maxChannelError", maxError);
        metrics.put("rmse", rmse);
        metrics.put("psnrDb", psnr);
        metrics.put("ssim", ssim(bytes, expected));
        metrics.put("normalization", "raw-byte-order");
        boolean passed = mismatchPixels == 0;
        return passed
                ? ExpectationResult.pass("image", "image contract satisfied", metrics)
                : ExpectationResult.fail("image", "image pixels differ", metrics);
    }

    private ExpectationResult evaluateNormalized(final CapturedResource actual) {
        int width = actual.width();
        int height = actual.height();
        byte[] bytes = actual.bytes();
        int mismatchPixels = 0;
        int compared = 0;
        int maxError = 0;
        double squaredError = 0.0;
        double[] actualLuma = new double[width * height];
        double[] expectedLuma = new double[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int actualOffset = pixelOffset(x, y, width, height, channelCount, actualNormalization);
                int expectedOffset = pixelOffset(x, y, width, height, channelCount, expectedNormalization);
                boolean mismatch = false;
                double actualRed = 0.0;
                double expectedRed = 0.0;
                double actualGreen = 0.0;
                double expectedGreen = 0.0;
                double actualBlue = 0.0;
                double expectedBlue = 0.0;
                for (int channel = 0; channel < channelCount; channel++) {
                    if (ignoreAlpha && channel == 3) continue;
                    int actualComponent = componentIndex(actualNormalization.channelOrder(), channel);
                    int expectedComponent = componentIndex(expectedNormalization.channelOrder(), channel);
                    int actualValue = bytes[actualOffset + actualComponent] & 0xff;
                    int expectedValue = expected[expectedOffset + expectedComponent] & 0xff;
                    double error = encodedError(
                            actualValue,
                            expectedValue,
                            actualNormalization.colorSpace(),
                            expectedNormalization.colorSpace()
                    );
                    int displayError = (int) Math.round(error);
                    maxError = Math.max(maxError, displayError);
                    squaredError += error * error;
                    compared++;
                    mismatch |= error > perChannelTolerance;
                    if (channel == 0) {
                        actualRed = normalizedColor(actualValue, actualNormalization.colorSpace());
                        expectedRed = normalizedColor(expectedValue, expectedNormalization.colorSpace());
                    } else if (channel == 1) {
                        actualGreen = normalizedColor(actualValue, actualNormalization.colorSpace());
                        expectedGreen = normalizedColor(expectedValue, expectedNormalization.colorSpace());
                    } else if (channel == 2) {
                        actualBlue = normalizedColor(actualValue, actualNormalization.colorSpace());
                        expectedBlue = normalizedColor(expectedValue, expectedNormalization.colorSpace());
                    }
                }
                if (channelCount > 1) {
                    actualLuma[y * width + x] = 0.2126 * actualRed + 0.7152 * actualGreen + 0.0722 * actualBlue;
                    expectedLuma[y * width + x] = 0.2126 * expectedRed + 0.7152 * expectedGreen + 0.0722 * expectedBlue;
                } else {
                    actualLuma[y * width + x] = actualRed;
                    expectedLuma[y * width + x] = expectedRed;
                }
                if (mismatch) mismatchPixels++;
            }
        }
        double rmse = compared == 0 ? 0.0 : Math.sqrt(squaredError / compared);
        double mse = compared == 0 ? 0.0 : squaredError / compared;
        Object psnr = mse == 0.0 ? "infinite" : 20.0 * Math.log10(255.0 / Math.sqrt(mse));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("mismatchPixels", mismatchPixels);
        metrics.put("comparedChannels", compared);
        metrics.put("maxChannelError", maxError);
        metrics.put("rmse", rmse);
        metrics.put("psnrDb", psnr);
        metrics.put("ssim", ssim(actualLuma, expectedLuma));
        metrics.put("normalization", "canonical-top-left");
        metrics.put("actualChannelOrder", actualNormalization.channelOrder().name());
        metrics.put("expectedChannelOrder", expectedNormalization.channelOrder().name());
        metrics.put("actualOrientation", actualNormalization.orientation().name());
        metrics.put("expectedOrientation", expectedNormalization.orientation().name());
        metrics.put("actualColorSpace", actualNormalization.colorSpace().name());
        metrics.put("expectedColorSpace", expectedNormalization.colorSpace().name());
        boolean passed = mismatchPixels == 0;
        return passed
                ? ExpectationResult.pass("image", "normalized image contract satisfied", metrics)
                : ExpectationResult.fail("image", "normalized image pixels differ", metrics);
    }

    @Override
    public byte[] expectedBytes() {
        return expected.clone();
    }

    public int channelCount() {
        return channelCount;
    }

    public boolean ignoreAlpha() {
        return ignoreAlpha;
    }

    public ImageNormalization actualNormalization() {
        return actualNormalization;
    }

    public ImageNormalization expectedNormalization() {
        return expectedNormalization;
    }

    private static int pixelOffset(
            final int x,
            final int y,
            final int width,
            final int height,
            final int channelCount,
            final ImageNormalization normalization
    ) {
        int sourceY = normalization.orientation() == ImageNormalization.Orientation.BOTTOM_LEFT
                ? height - 1 - y
                : y;
        return (sourceY * width + x) * channelCount;
    }

    private static int componentIndex(
            final ImageNormalization.ChannelOrder order,
            final int canonicalChannel
    ) {
        if (order == ImageNormalization.ChannelOrder.BGRA
                || order == ImageNormalization.ChannelOrder.BGR) {
            if (canonicalChannel == 0) return 2;
            if (canonicalChannel == 2) return 0;
        }
        return canonicalChannel;
    }

    private static double encodedError(
            final int actual,
            final int expected,
            final ImageNormalization.ColorSpace actualColorSpace,
            final ImageNormalization.ColorSpace expectedColorSpace
    ) {
        if (actualColorSpace == expectedColorSpace) {
            return Math.abs(actual - expected);
        }
        return Math.abs(
                normalizedColor(actual, actualColorSpace) - normalizedColor(expected, expectedColorSpace)
        ) * 255.0;
    }

    private static double normalizedColor(
            final int value,
            final ImageNormalization.ColorSpace colorSpace
    ) {
        double encoded = value / 255.0;
        if (colorSpace != ImageNormalization.ColorSpace.SRGB) {
            return encoded;
        }
        return encoded <= 0.04045
                ? encoded / 12.92
                : Math.pow((encoded + 0.055) / 1.055, 2.4);
    }

    private double ssim(final byte[] actual, final byte[] reference) {
        int pixels = actual.length / channelCount;
        if (pixels == 0) return 1.0;
        double actualMean = 0.0;
        double referenceMean = 0.0;
        double[] actualLuma = new double[pixels];
        double[] referenceLuma = new double[pixels];
        for (int pixel = 0; pixel < pixels; pixel++) {
            int offset = pixel * channelCount;
            double a = (actual[offset] & 0xff);
            double r = (reference[offset] & 0xff);
            if (channelCount > 1) {
                a = 0.2126 * a + 0.7152 * (actual[offset + 1] & 0xff)
                        + 0.0722 * (actual[offset + 2] & 0xff);
                r = 0.2126 * r + 0.7152 * (reference[offset + 1] & 0xff)
                        + 0.0722 * (reference[offset + 2] & 0xff);
            }
            actualLuma[pixel] = a;
            referenceLuma[pixel] = r;
            actualMean += a;
            referenceMean += r;
        }
        actualMean /= pixels;
        referenceMean /= pixels;
        double actualVariance = 0.0;
        double referenceVariance = 0.0;
        double covariance = 0.0;
        for (int pixel = 0; pixel < pixels; pixel++) {
            double actualDelta = actualLuma[pixel] - actualMean;
            double referenceDelta = referenceLuma[pixel] - referenceMean;
            actualVariance += actualDelta * actualDelta;
            referenceVariance += referenceDelta * referenceDelta;
            covariance += actualDelta * referenceDelta;
        }
        double divisor = Math.max(1, pixels - 1);
        actualVariance /= divisor;
        referenceVariance /= divisor;
        covariance /= divisor;
        double c1 = 6.5025;
        double c2 = 58.5225;
        return ((2.0 * actualMean * referenceMean + c1) * (2.0 * covariance + c2))
                / ((actualMean * actualMean + referenceMean * referenceMean + c1)
                * (actualVariance + referenceVariance + c2));
    }

    private double ssim(final double[] actual, final double[] reference) {
        if (actual.length == 0) return 1.0;
        double actualMean = 0.0;
        double referenceMean = 0.0;
        for (int index = 0; index < actual.length; index++) {
            actualMean += actual[index];
            referenceMean += reference[index];
        }
        actualMean /= actual.length;
        referenceMean /= reference.length;
        double actualVariance = 0.0;
        double referenceVariance = 0.0;
        double covariance = 0.0;
        for (int index = 0; index < actual.length; index++) {
            double actualDelta = actual[index] - actualMean;
            double referenceDelta = reference[index] - referenceMean;
            actualVariance += actualDelta * actualDelta;
            referenceVariance += referenceDelta * referenceDelta;
            covariance += actualDelta * referenceDelta;
        }
        double divisor = Math.max(1, actual.length - 1);
        actualVariance /= divisor;
        referenceVariance /= divisor;
        covariance /= divisor;
        double c1 = 6.5025 / (255.0 * 255.0);
        double c2 = 58.5225 / (255.0 * 255.0);
        return ((2.0 * actualMean * referenceMean + c1) * (2.0 * covariance + c2))
                / ((actualMean * actualMean + referenceMean * referenceMean + c1)
                * (actualVariance + referenceVariance + c2));
    }
}
