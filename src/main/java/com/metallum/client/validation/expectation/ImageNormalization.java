package com.metallum.client.validation.expectation;

import com.metallum.client.validation.contract.CaptureFormat;

/**
 * Declares how an image byte stream is interpreted before comparison.
 *
 * <p>The raw form exists only for compatibility with legacy fixtures. New
 * image contracts should declare channel order, origin, and color space
 * explicitly so a BGRA/Y-flipped readback cannot accidentally pass as RGBA.</p>
 */
public record ImageNormalization(
        ChannelOrder channelOrder,
        Orientation orientation,
        ColorSpace colorSpace
) {
    public enum ChannelOrder {
        RGBA,
        BGRA,
        RGB,
        BGR,
        RAW
    }

    public enum Orientation {
        TOP_LEFT,
        BOTTOM_LEFT,
        UNSPECIFIED
    }

    public enum ColorSpace {
        SRGB,
        LINEAR,
        UNKNOWN
    }

    public ImageNormalization {
        if (channelOrder == null || orientation == null || colorSpace == null) {
            throw new IllegalArgumentException("Image normalization fields must not be null");
        }
    }

    public static ImageNormalization raw(final int channelCount) {
        validateChannelCount(channelCount);
        return new ImageNormalization(ChannelOrder.RAW, Orientation.UNSPECIFIED, ColorSpace.UNKNOWN);
    }

    public static ImageNormalization canonicalSrgb(final int channelCount) {
        return new ImageNormalization(orderFor(channelCount), Orientation.TOP_LEFT, ColorSpace.SRGB);
    }

    public static ImageNormalization canonicalLinear(final int channelCount) {
        return new ImageNormalization(orderFor(channelCount), Orientation.TOP_LEFT, ColorSpace.LINEAR);
    }

    public static ImageNormalization fromCaptureFormat(final CaptureFormat format) {
        if (format == null) {
            throw new IllegalArgumentException("Capture format must not be null");
        }
        String name = format.name().toUpperCase(java.util.Locale.ROOT);
        ChannelOrder order;
        if (name.startsWith("BGRA")) {
            order = ChannelOrder.BGRA;
        } else if (name.startsWith("RGBA")) {
            order = ChannelOrder.RGBA;
        } else if (name.startsWith("BGR")) {
            order = ChannelOrder.BGR;
        } else if (name.startsWith("RGB")) {
            order = ChannelOrder.RGB;
        } else {
            order = ChannelOrder.RAW;
        }
        ColorSpace colorSpace = name.contains("SRGB") ? ColorSpace.SRGB : ColorSpace.UNKNOWN;
        return new ImageNormalization(order, Orientation.UNSPECIFIED, colorSpace);
    }

    public boolean isRaw() {
        return channelOrder == ChannelOrder.RAW
                && orientation == Orientation.UNSPECIFIED
                && colorSpace == ColorSpace.UNKNOWN;
    }

    public boolean isFullySpecified(final int channelCount) {
        return !isRaw()
                && (channelCount == 4
                        ? channelOrder == ChannelOrder.RGBA || channelOrder == ChannelOrder.BGRA
                        : channelOrder == ChannelOrder.RGB || channelOrder == ChannelOrder.BGR)
                && orientation != Orientation.UNSPECIFIED
                && colorSpace != ColorSpace.UNKNOWN;
    }

    private static ChannelOrder orderFor(final int channelCount) {
        validateChannelCount(channelCount);
        return channelCount == 4 ? ChannelOrder.RGBA : ChannelOrder.RGB;
    }

    private static void validateChannelCount(final int channelCount) {
        if (channelCount != 3 && channelCount != 4) {
            throw new IllegalArgumentException("Image normalization supports RGB or RGBA only");
        }
    }
}
