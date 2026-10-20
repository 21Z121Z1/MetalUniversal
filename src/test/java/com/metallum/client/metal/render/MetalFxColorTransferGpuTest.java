package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.joml.Vector4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** Runs shipping shaders without requiring a Temporal-capable device. */
@EnabledOnOs(OS.MAC)
final class MetalFxColorTransferGpuTest {
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach void setup() {
        ShaderSource sources = MetalShaderSourceAdapters.from((id, type) -> {
            String resource = "assets/" + id.getNamespace() + "/shaders/" + id.getPath()
                    + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
            try { return MetalMotionShaderCompilationTest.expandIncludes(resource, new HashSet<>()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
        device = new MetalDevice(sources, new GpuDebugOptions(2, true, true, true),
                MetalNativeBridge.metallum_create_system_default_device(), MemorySegment.NULL,
                "Temporal color boundary", MemorySegment.NULL);
        encoder = device.commandEncoder();
    }

    @AfterEach void close() { if (device != null) device.close(); }

    @Test void grayscaleRampHasIndependentLinearValuesAndRoundTripsAllChannels() throws Exception {
        // Odd, nonsquare dimensions and distinct rows detect coordinate/Y mistakes.
        int width = 257, height = 3;
        ByteBuffer source = ByteBuffer.allocateDirect(width * height * 4);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            source.put((byte)Math.min(x,255)).put((byte)(255-Math.min(x,255)))
                    .put((byte)(y*91)).put((byte)((x+y*37)&255));
        }
        source.flip();
        try (var input = texture("display ramp", GpuFormat.RGBA8_UNORM, width, height);
             var output = texture("display output", GpuFormat.RGBA8_UNORM, width, height);
             var transfer = new MetalFxColorTransfer(device)) {
            encoder.writeToTexture(input, source, 0, 0, 0, 0, width, height);
            var linear = transfer.decode(encoder, input, width, height);
            byte[] actualLinear = readback(linear);
            ByteBuffer half = ByteBuffer.wrap(actualLinear).order(ByteOrder.nativeOrder());
            double maxError = 0;
            for (int p = 0; p < width*height; p++) for (int c = 0; c < 4; c++) {
                double encoded = Byte.toUnsignedInt(source.get(p*4+c))/255.0;
                double expected = c == 3 ? encoded : encoded <= .04045
                        ? encoded/12.92 : Math.pow((encoded+.055)/1.055,2.4);
                double actual = Float.float16ToFloat(half.getShort((p*4+c)*2));
                maxError = Math.max(maxError, Math.abs(expected-actual));
                assertEquals(expected, actual, .00055, "independent IEC sRGB decode/alpha, p="+p+" c="+c);
            }
            assertEquals(.21586, Float.float16ToFloat(half.getShort(128*8)), .00055,
                    "negative control: passthrough gamma .502 must not masquerade as linear");
            assertTrue(encoder.encodeTextureCopy(linear, transfer.output(), false));
            transfer.encode(encoder, output);
            byte[] roundTrip = readback(output);
            for (int i = 0; i < roundTrip.length; i++) {
                assertEquals(Byte.toUnsignedInt(source.get(i)), Byte.toUnsignedInt(roundTrip[i]), 1,
                        "round trip and alpha channel at byte "+i);
            }
            Path dir = Path.of("build/agent-evidence/metalfx-color-transfer");
            Files.createDirectories(dir);
            byte[] inputBytes = new byte[source.remaining()];
            source.duplicate().get(inputBytes);
            Files.write(dir.resolve("input.rgba8"), inputBytes);
            Files.write(dir.resolve("linear.rgba16f"), actualLinear);
            Files.write(dir.resolve("output.rgba8"), roundTrip);
            Files.writeString(dir.resolve("ramp.json"), "{\"sourceCommit\":\""+System.getProperty("metallum.validation.sourceCommit",
                    System.getenv().getOrDefault("METALLUM_SOURCE_SHA", "unknown"))
                    +"\",\"width\":"+width+",\"height\":"+height+",\"maxLinearError\":"+maxError+"}");
        }
    }

    @Test void sdrOvershootClampsAndResizeRejectsStaleOutput() {
        try (var input = texture("source", GpuFormat.RGBA8_UNORM, 5, 3);
             var output = texture("output", GpuFormat.RGBA8_UNORM, 7, 5);
             var wrong = texture("stale output", GpuFormat.RGBA8_UNORM, 5, 3);
             var transfer = new MetalFxColorTransfer(device)) {
            encoder.clearColorTexture(input, new Vector4f(.5F));
            var old = transfer.decode(encoder, input, 5, 3);
            transfer.decode(encoder, input, 7, 5);
            assertTrue(old.isClosed());
            assertThrows(IllegalArgumentException.class, () -> transfer.encode(encoder, wrong));
            encoder.clearColorTexture(transfer.output(), new Vector4f(-.01F, 1.1F, .0031308F, .25F));
            transfer.encode(encoder, output);
            byte[] values = readback(output);
            for (int p = 0; p < 7*5; p++) {
                assertEquals(0, Byte.toUnsignedInt(values[p*4]));
                assertEquals(255, Byte.toUnsignedInt(values[p*4+1]));
                assertEquals(10, Byte.toUnsignedInt(values[p*4+2]),1);
                assertEquals(64, Byte.toUnsignedInt(values[p*4+3]),1);
            }
        }
    }

    private MetalGpuTexture texture(String label, GpuFormat format, int width, int height) {
        return (MetalGpuTexture) device.createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                format, width, height, 1, 1);
    }

    private byte[] readback(MetalGpuTexture texture) {
        int size = texture.getWidth(0)*texture.getHeight(0)*texture.pixelSize();
        try (var buffer = (MetalGpuBuffer)device.createBuffer(() -> "color transfer readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size)) {
            encoder.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0);
            encoder.submit(); device.waitForSubmittedGpuWork();
            byte[] bytes = new byte[size]; buffer.currentStorage().get(0,bytes); return bytes;
        }
    }
}
