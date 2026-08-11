package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalRuntimeReceiptsTest {
    @Test
    void metricsProveNonBlackAndFrameProgression() {
        byte[] first = {
                0, 0, 0, (byte) 255,
                10, 20, 30, (byte) 255
        };
        byte[] second = {
                0, 0, 0, (byte) 255,
                40, 20, 30, (byte) 255
        };

        IrisMetalRuntimeReceipts.FrameMetrics initial = IrisMetalRuntimeReceipts.analyze(
                first, 2, 1, 4, null
        );
        IrisMetalRuntimeReceipts.FrameMetrics next = IrisMetalRuntimeReceipts.analyze(
                second, 2, 1, 4, first
        );

        assertFalse(initial.hasPreviousFrame());
        assertEquals(1, initial.nonBlackRgbPixels());
        assertEquals(30, initial.maxByte());
        assertEquals(60, initial.sumRgbBytes());
        assertTrue(next.hasPreviousFrame());
        assertEquals(1, next.nonBlackRgbPixels());
        assertEquals(1, next.changedPixels());
        assertEquals(30.0 / 8.0, next.meanAbsoluteByteDelta(), 0.000001);
        assertEquals(90, next.sumRgbBytes());
        assertFalse(next.sha256().equals(initial.sha256()));
    }

    @Test
    void failureReceiptMethodRequiresAConcretePhaseAndCause() throws Exception {
        Method method = IrisMetalRuntimeReceipts.class.getDeclaredMethod(
                "recordFailure", String.class, Throwable.class
        );
        assertTrue(method.getReturnType() == void.class);
    }

    @Test
    void deviceDescriptorPreservesReceiptType() {
        var receipt = new com.google.gson.JsonObject();
        receipt.addProperty("type", "device");
        DeviceInfo info = new DeviceInfo(
                "Test Metal",
                "Apple",
                "macOS test",
                true,
                "Metal",
                1.0F,
                new DeviceLimits(
                        16,
                        256,
                        16384,
                        1024L,
                        0,
                        ColorTargetState.MAX_COLOR_TARGETS
                ),
                new DeviceFeatures(false, false, true, true, true, false, true),
                Set.of("MTLDevice"),
                new com.mojang.blaze3d.systems.HintsAndWorkarounds(false, false),
                DeviceType.INTEGRATED
        );

        IrisMetalRuntimeReceipts.addDeviceDescriptor(receipt, info);

        assertEquals("device", receipt.get("type").getAsString());
        assertEquals("INTEGRATED", receipt.get("deviceType").getAsString());
    }

    @Test
    void candidateDiscardReceiptCarriesRebuildContext(@TempDir Path tempDir) throws IOException {
        String property = "metallum.iris.validation.receipt";
        String previous = System.getProperty(property);
        Path receiptPath = tempDir.resolve("iris-receipt.jsonl");
        try {
            System.setProperty(property, receiptPath.toString());
            IrisMetalRuntimeReceipts receipts = IrisMetalRuntimeReceipts.open(7);
            receipts.recordGenerationCandidateFailure(
                    "resize",
                    new IllegalStateException("candidate texture allocation failed"),
                    1280,
                    720,
                    true,
                    false
            );
            receipts.close();

            List<JsonObject> objects = Files.readAllLines(receiptPath).stream()
                    .map(JsonParser::parseString)
                    .map(element -> element.getAsJsonObject())
                    .toList();
            JsonObject discarded = objects.stream()
                    .filter(object -> object.has("event")
                            && "candidate-discarded".equals(object.get("event").getAsString()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing candidate-discarded receipt"));
            assertEquals("generation", discarded.get("type").getAsString());
            assertEquals("resize", discarded.get("phase").getAsString());
            assertEquals(1280, discarded.get("width").getAsInt());
            assertEquals(720, discarded.get("height").getAsInt());
            assertTrue(discarded.get("resizing").getAsBoolean());
            assertFalse(discarded.get("deviceReplacement").getAsBoolean());
            assertTrue(discarded.get("error").getAsString().contains("candidate texture allocation"));
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void colorSpaceReceiptRecordsConversionAndPackOwnership(@TempDir Path tempDir) throws IOException {
        String property = "metallum.iris.validation.receipt";
        String previous = System.getProperty(property);
        Path receiptPath = tempDir.resolve("iris-color-space-receipt.jsonl");
        try {
            System.setProperty(property, receiptPath.toString());
            IrisMetalRuntimeReceipts receipts = IrisMetalRuntimeReceipts.open(9);
            receipts.recordColorSpaceFinalization(ColorSpace.DISPLAY_P3, true, false);
            receipts.recordColorSpaceFinalization(ColorSpace.REC2020, false, true);
            receipts.close();

            List<JsonObject> objects = Files.readAllLines(receiptPath).stream()
                    .map(JsonParser::parseString)
                    .map(element -> element.getAsJsonObject())
                    .toList();
            List<JsonObject> colorSpaceEvents = objects.stream()
                    .filter(object -> object.has("event")
                            && "color-space.finalized".equals(object.get("event").getAsString()))
                    .toList();
            assertEquals(2, colorSpaceEvents.size());
            assertEquals("DISPLAY_P3", colorSpaceEvents.get(0).get("colorSpace").getAsString());
            assertTrue(colorSpaceEvents.get(0).get("conversionExecuted").getAsBoolean());
            assertFalse(colorSpaceEvents.get(0).get("packOwnedBypass").getAsBoolean());
            assertEquals("iris-conversion", colorSpaceEvents.get(0).get("mode").getAsString());
            assertEquals("REC2020", colorSpaceEvents.get(1).get("colorSpace").getAsString());
            assertFalse(colorSpaceEvents.get(1).get("conversionExecuted").getAsBoolean());
            assertTrue(colorSpaceEvents.get(1).get("packOwnedBypass").getAsBoolean());
            assertEquals("pack-owned-bypass", colorSpaceEvents.get(1).get("mode").getAsString());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }
}
