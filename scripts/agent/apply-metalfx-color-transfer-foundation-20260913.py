#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))

# 1) Native: add reusable sRGB <-> linear transfer pipeline and presenter dual-format UI staging.
swift = "src/main/native/MetallumNative.swift"
replace_once(
    swift,
    "    static var handOverlayPipeline: MTLComputePipelineState?\n    static var metalFxFailureKeys: Set<String> = []",
    "    static var handOverlayPipeline: MTLComputePipelineState?\n"
    "    // Explicit transfer-function bridge around MetalFX Temporal. Minecraft's scene\n"
    "    // target stores display-referred sRGB numeric values in plain RGBA8_UNORM;\n"
    "    // Temporal itself requires linear numeric input/output. This device-scoped\n"
    "    // compute PSO performs only the transfer function, preserving alpha.\n"
    "    static var colorTransferPipeline: MTLComputePipelineState?\n"
    "    static var metalFxFailureKeys: Set<String> = []"
)

color_transfer_code = r'''
private struct MetalFxColorTransferUniforms {
    var viewport: SIMD2<UInt32>
    /// 0 = display-sRGB numeric values -> linear, 1 = linear -> display-sRGB numeric values.
    var mode: UInt32
    var padding: UInt32 = 0
}

private func colorTransferMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct ColorTransferUniforms {
      uint2 viewport;
      uint mode;
      uint padding;
    };

    inline float metallum_srgb_decode(float x) {
      x = clamp(x, 0.0f, 1.0f);
      return x <= 0.04045f
        ? x / 12.92f
        : pow((x + 0.055f) / 1.055f, 2.4f);
    }

    inline float metallum_srgb_encode(float x) {
      x = clamp(x, 0.0f, 1.0f);
      return x <= 0.0031308f
        ? x * 12.92f
        : 1.055f * pow(x, 1.0f / 2.4f) - 0.055f;
    }

    kernel void metallum_color_transfer(
      texture2d<float, access::read> sourceTexture [[texture(0)]],
      texture2d<float, access::write> destinationTexture [[texture(1)]],
      constant ColorTransferUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      if (pixel.x >= u.viewport.x || pixel.y >= u.viewport.y) return;
      float4 sample = sourceTexture.read(pixel);
      float3 rgb;
      if (u.mode == 0u) {
        rgb = float3(
          metallum_srgb_decode(sample.r),
          metallum_srgb_decode(sample.g),
          metallum_srgb_decode(sample.b)
        );
      } else {
        rgb = float3(
          metallum_srgb_encode(sample.r),
          metallum_srgb_encode(sample.g),
          metallum_srgb_encode(sample.b)
        );
      }
      // Alpha is coverage/compositing data, not color, and must never pass
      // through an RGB transfer function.
      destinationTexture.write(float4(rgb, sample.a), pixel);
    }
    """
}

private func ensureColorTransferPipeline(_ device: MTLDevice) -> MTLComputePipelineState? {
    if let existing = NativeState.colorTransferPipeline {
        return existing
    }
    do {
        let library = try device.makeLibrary(source: colorTransferMslSource(), options: nil)
        guard let function = library.makeFunction(name: "metallum_color_transfer") else {
            return nil
        }
        let pipeline = try device.makeComputePipelineState(function: function)
        residencyTrackCreated(pipeline)
        NativeState.colorTransferPipeline = pipeline
        return pipeline
    } catch {
        #if os(macOS) && canImport(MetalFX)
        logMetalFxFailureOnce(
            "color-transfer-pipeline",
            "failed to build explicit sRGB transfer pipeline: \(error)"
        )
        #endif
        return nil
    }
}

private func metal3MetalFxColorTransfer(
    _ commandBuffer: MTLCommandBuffer,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ mode: Int32,
    _ fence: MTLFence?
) -> Int32 {
    guard mode == 0 || mode == 1,
          sourceTexture.width == destinationTexture.width,
          sourceTexture.height == destinationTexture.height,
          sourceTexture.width > 0,
          sourceTexture.height > 0,
          let pipeline = ensureColorTransferPipeline(commandBuffer.device),
          let encoder = commandBuffer.makeComputeCommandEncoder() else {
        return 0
    }
    encoder.label = mode == 0
        ? "MetalFX sRGB Decode -> Linear"
        : "MetalFX Linear -> sRGB Encode"
    metal4BarrierComputeAfterRender(encoder)
    if let fence {
        encoder.waitForFence(fence)
    }
    var uniforms = MetalFxColorTransferUniforms(
        viewport: SIMD2(UInt32(sourceTexture.width), UInt32(sourceTexture.height)),
        mode: UInt32(mode)
    )
    encoder.setComputePipelineState(pipeline)
    encoder.setBytes(&uniforms, length: MemoryLayout<MetalFxColorTransferUniforms>.stride, index: 0)
    encoder.setTexture(sourceTexture, index: 0)
    encoder.setTexture(destinationTexture, index: 1)
    let threadWidth = max(1, min(pipeline.threadExecutionWidth, 64))
    let threadHeight = max(1, min(8, pipeline.maxTotalThreadsPerThreadgroup / threadWidth))
    encoder.dispatchThreads(
        MTLSize(width: sourceTexture.width, height: sourceTexture.height, depth: 1),
        threadsPerThreadgroup: MTLSize(width: threadWidth, height: threadHeight, depth: 1)
    )
    if let fence {
        encoder.updateFence(fence)
    }
    encoder.endEncoding()
    return 1
}

/// Explicit numeric transfer-function conversion used at the Temporal boundary.
/// This is intentionally separate from texture-copy/resample: sampling an
/// RGBA8_UNORM texture never implies sRGB decoding because the source view is
/// not an `_srgb` pixel format.
@_cdecl("metallum_metalfx_color_transfer")
public func metallumMetalFxColorTransferEntry(
    _ commandBufferPointer: UnsafeMutableRawPointer,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ mode: Int32,
    _ fence: MTLFence?
) -> Int32 {
    guard mode == 0 || mode == 1,
          sourceTexture.width == destinationTexture.width,
          sourceTexture.height == destinationTexture.height,
          sourceTexture.width > 0,
          sourceTexture.height > 0 else {
        return 0
    }
    if #available(macOS 26.0, iOS 26.0, *),
       let lease = metal4MainLease(commandBufferPointer),
       let pipeline = ensureColorTransferPipeline(sourceTexture.device) {
        let uniforms = MetalFxColorTransferUniforms(
            viewport: SIMD2(UInt32(sourceTexture.width), UInt32(sourceTexture.height)),
            mode: UInt32(mode)
        )
        return encodeMetal4Compute(
            lease: lease,
            label: mode == 0
                ? "MetalFX sRGB Decode -> Linear (Metal 4)"
                : "MetalFX Linear -> sRGB Encode (Metal 4)",
            pipeline: pipeline,
            uniforms: uniforms,
            textures: [(0, sourceTexture), (1, destinationTexture)],
            width: sourceTexture.width,
            height: sourceTexture.height
        ) ? 1 : 0
    }
    return metal3MetalFxColorTransfer(
        metal3CommandBuffer(commandBufferPointer),
        sourceTexture,
        destinationTexture,
        mode,
        fence
    )
}

'''
replace_once(
    swift,
    "private func motionClearV2MslSource() -> String {",
    color_transfer_code + "private func motionClearV2MslSource() -> String {"
)

replace_once(
    swift,
    "    residencyTrackReleased(NativeState.handOverlayPipeline)\n    NativeState.motionPipeline = nil",
    "    residencyTrackReleased(NativeState.handOverlayPipeline)\n"
    "    residencyTrackReleased(NativeState.colorTransferPipeline)\n"
    "    NativeState.motionPipeline = nil"
)
replace_once(
    swift,
    "    NativeState.cutoutReactivePipeline = nil\n    NativeState.frameGenerationLogged = false",
    "    NativeState.cutoutReactivePipeline = nil\n"
    "    NativeState.handOverlayPipeline = nil\n"
    "    NativeState.colorTransferPipeline = nil\n"
    "    NativeState.frameGenerationLogged = false"
)

# Presenter: keep scene/native scene in outputFormat, but stage UI in its own format.
replace_once(
    swift,
    "    private var outputFormat: MTLPixelFormat\n    private var depthFormat: MTLPixelFormat",
    "    private var outputFormat: MTLPixelFormat\n"
    "    private var uiFormat: MTLPixelFormat\n"
    "    private var depthFormat: MTLPixelFormat"
)
replace_once(
    swift,
    "        guard nativeSceneColor.width == uiColor.width,\n              nativeSceneColor.height == uiColor.height,\n              nativeSceneColor.pixelFormat == uiColor.pixelFormat else {",
    "        guard nativeSceneColor.width == uiColor.width,\n"
    "              nativeSceneColor.height == uiColor.height,\n"
    "              nativeSceneColor.pixelFormat == sceneColor.pixelFormat else {"
)
replace_once(
    swift,
    "        self.outputFormat = sceneColor.pixelFormat\n        self.depthFormat = depth.pixelFormat",
    "        self.outputFormat = sceneColor.pixelFormat\n"
    "        self.uiFormat = uiColor.pixelFormat\n"
    "        self.depthFormat = depth.pixelFormat"
)
replace_once(
    swift,
    "            outputFormat: sceneColor.pixelFormat,\n            depthFormat: depth.pixelFormat,",
    "            outputFormat: sceneColor.pixelFormat,\n"
    "            uiFormat: uiColor.pixelFormat,\n"
    "            depthFormat: depth.pixelFormat,"
)
# Function declarations/calls: makeTextureSet, rebuildTextures, resizeResources.
replace_once(
    swift,
    "        outputFormat: MTLPixelFormat,\n        depthFormat: MTLPixelFormat,\n        motionFormat: MTLPixelFormat,\n        depthWidth: Int,",
    "        outputFormat: MTLPixelFormat,\n"
    "        uiFormat: MTLPixelFormat,\n"
    "        depthFormat: MTLPixelFormat,\n"
    "        motionFormat: MTLPixelFormat,\n"
    "        depthWidth: Int,"
)
replace_once(
    swift,
    "            ), let uiOverlay = makeTexture(\n                pixelFormat: outputFormat,",
    "            ), let uiOverlay = makeTexture(\n                pixelFormat: uiFormat,"
)
# First makeTextureSet call inside rebuildTextures.
replace_once(
    swift,
    "            outputFormat: outputFormat,\n            depthFormat: depthFormat,\n            motionFormat: motionFormat,\n            depthWidth: depthWidth,",
    "            outputFormat: outputFormat,\n"
    "            uiFormat: uiFormat,\n"
    "            depthFormat: depthFormat,\n"
    "            motionFormat: motionFormat,\n"
    "            depthWidth: depthWidth,"
)
# installTextureSet signature and state.
replace_once(
    swift,
    "        outputFormat: MTLPixelFormat,\n        depthFormat: MTLPixelFormat,\n        motionFormat: MTLPixelFormat\n    ) {",
    "        outputFormat: MTLPixelFormat,\n"
    "        uiFormat: MTLPixelFormat,\n"
    "        depthFormat: MTLPixelFormat,\n"
    "        motionFormat: MTLPixelFormat\n    ) {"
)
replace_once(
    swift,
    "        self.outputFormat = outputFormat\n        self.depthFormat = depthFormat",
    "        self.outputFormat = outputFormat\n"
    "        self.uiFormat = uiFormat\n"
    "        self.depthFormat = depthFormat"
)
# First install call in rebuildTextures.
replace_once(
    swift,
    "            outputFormat: outputFormat,\n            depthFormat: depthFormat,\n            motionFormat: motionFormat\n        )\n        return true",
    "            outputFormat: outputFormat,\n"
    "            uiFormat: uiFormat,\n"
    "            depthFormat: depthFormat,\n"
    "            motionFormat: motionFormat\n        )\n        return true"
)
# resizeResources signature.
replace_once(
    swift,
    "        outputFormat: MTLPixelFormat,\n        depth: MTLTexture,\n        motion: MTLTexture,",
    "        outputFormat: MTLPixelFormat,\n"
    "        uiFormat: MTLPixelFormat,\n"
    "        depth: MTLTexture,\n"
    "        motion: MTLTexture,"
)
# makeTextureSet within resizeResources (second occurrence now unique with depth.pixelFormat).
replace_once(
    swift,
    "            outputFormat: outputFormat,\n            depthFormat: depth.pixelFormat,\n            motionFormat: motion.pixelFormat,",
    "            outputFormat: outputFormat,\n"
    "            uiFormat: uiFormat,\n"
    "            depthFormat: depth.pixelFormat,\n"
    "            motionFormat: motion.pixelFormat,"
)
# install within resizeResources.
replace_once(
    swift,
    "            outputFormat: outputFormat,\n            depthFormat: depth.pixelFormat,\n            motionFormat: motion.pixelFormat\n        )\n        self.frameInterpolator",
    "            outputFormat: outputFormat,\n"
    "            uiFormat: uiFormat,\n"
    "            depthFormat: depth.pixelFormat,\n"
    "            motionFormat: motion.pixelFormat\n        )\n        self.frameInterpolator"
)
# Encode guard/rebuild policy.
replace_once(
    swift,
    "              sceneColor.pixelFormat == uiColor.pixelFormat,\n              nativeSceneColor.pixelFormat == uiColor.pixelFormat,",
    "              sceneColor.pixelFormat == nativeSceneColor.pixelFormat,"
)
replace_once(
    swift,
    "                || sceneColor.pixelFormat != outputFormat\n                || depth.pixelFormat != depthFormat || motion.pixelFormat != motionFormat",
    "                || sceneColor.pixelFormat != outputFormat\n"
    "                || uiColor.pixelFormat != uiFormat\n"
    "                || depth.pixelFormat != depthFormat || motion.pixelFormat != motionFormat"
)
replace_once(
    swift,
    "                outputFormat: sceneColor.pixelFormat,\n                depth: depth,",
    "                outputFormat: sceneColor.pixelFormat,\n"
    "                uiFormat: uiColor.pixelFormat,\n"
    "                depth: depth,"
)

# 2) Java bridge: optional, fail-closed native transfer symbol.
bridge = "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
replace_once(
    bridge,
    "            metalfxCopy = downcallWithoutCritical(lookup, \"metallum_encode_texture_copy\", FunctionDescriptor.of(\n                    INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS\n            ));",
    "            metalfxCopy = downcallWithoutCritical(lookup, \"metallum_encode_texture_copy\", FunctionDescriptor.of(\n"
    "                    INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS\n"
    "            ));\n"
    "            metalfxColorTransfer = optionalDowncallWithoutCritical(\n"
    "                    lookup,\n"
    "                    \"metallum_metalfx_color_transfer\",\n"
    "                    FunctionDescriptor.of(\n"
    "                            INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS\n"
    "                    )\n"
    "            );"
)
replace_once(
    bridge,
    "    private static final MethodHandle metalfxCopy;\n    private static final MethodHandle metalfxShutdown;",
    "    private static final MethodHandle metalfxCopy;\n"
    "    @Nullable\n"
    "    private static final MethodHandle metalfxColorTransfer;\n"
    "    private static final MethodHandle metalfxShutdown;"
)
bridge_methods = r'''
    public static boolean metallum_metalfx_color_transfer_available() {
        return metalfxColorTransfer != null;
    }

    /** mode 0 decodes display-sRGB numeric RGB to linear; mode 1 encodes linear RGB to display-sRGB. */
    public static boolean metallum_metalfx_color_transfer(
            final MemorySegment commandBuffer,
            final MemorySegment source,
            final MemorySegment destination,
            final int mode,
            final MemorySegment fence
    ) {
        if (metalfxColorTransfer == null || (mode != 0 && mode != 1)) {
            return false;
        }
        try {
            return (int) metalfxColorTransfer.invokeExact(
                    segment(commandBuffer), segment(source), segment(destination), mode, segment(fence)
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_color_transfer", throwable);
        }
    }

'''
replace_once(
    bridge,
    "    public static boolean metallum_encode_texture_copy(\n",
    bridge_methods + "    public static boolean metallum_encode_texture_copy(\n"
)

# 3) Java command encoder seam. Keep the primitive inaccessible to generic game code.
encoder = "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java"
encoder_method = r'''
    boolean encodeMetalFxColorTransfer(
            final MetalGpuTexture source,
            final MetalGpuTexture destination,
            final boolean encodeSrgb
    ) {
        if (!MetalNativeBridge.metallum_metalfx_color_transfer_available()) {
            return false;
        }
        flushPendingClear(source);
        submitRenderPass();
        endEncoder();
        destination.markContentsDirty();
        boolean encoded = MetalNativeBridge.metallum_metalfx_color_transfer(
                commandBuffer().nativeHandle(),
                source.nativeHandle(),
                destination.nativeHandle(),
                encodeSrgb ? 1 : 0,
                fence
        );
        if (RenderContractRuntime.enabled()) {
            ResourceIdentity sourceIdentity = contractResource(source, 0);
            ResourceIdentity destinationIdentity = contractResource(destination, 0);
            RenderContractRuntime.recordTransfer(
                    PassType.COMPUTE,
                    encodeSrgb ? "metallum/metalfx-srgb-encode" : "metallum/metalfx-srgb-decode",
                    ProducerType.COPY,
                    List.of(destinationIdentity),
                    Map.of("encoded", Boolean.toString(encoded)),
                    Map.of("source", sourceIdentity.stableKey())
            );
        }
        return encoded;
    }

'''
replace_once(
    encoder,
    "    boolean encodeTextureCopy(final MetalGpuTexture source, final MetalGpuTexture destination, final boolean linear) {\n",
    encoder_method + "    boolean encodeTextureCopy(final MetalGpuTexture source, final MetalGpuTexture destination, final boolean linear) {\n"
)

# 4) Source-contract test: formulas, alpha semantics, Metal 3/4 seam, separate UI staging format.
test = Path("src/test/java/com/metallum/client/metal/render/MetalFxTemporalColorTransferSourceTest.java")
if test.exists():
    raise SystemExit(f"{test} already exists")
test.write_text(r'''package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxTemporalColorTransferSourceTest {
    private static String nativeSource() throws Exception {
        return Files.readString(Path.of("src/main/native/MetallumNative.swift"));
    }

    @Test
    void transferUsesCanonicalSrgbBreakpointsAndPreservesAlpha() throws Exception {
        String source = nativeSource();
        assertTrue(source.contains("x <= 0.04045f"));
        assertTrue(source.contains("x / 12.92f"));
        assertTrue(source.contains("(x + 0.055f) / 1.055f"));
        assertTrue(source.contains("2.4f"));
        assertTrue(source.contains("x <= 0.0031308f"));
        assertTrue(source.contains("x * 12.92f"));
        assertTrue(source.contains("1.055f * pow(x, 1.0f / 2.4f) - 0.055f"));
        assertTrue(source.contains("destinationTexture.write(float4(rgb, sample.a), pixel)"));
    }

    @Test
    void transferIsExplicitAndImplementedForBothCommandBufferBackends() throws Exception {
        String source = nativeSource();
        assertTrue(source.contains("@_cdecl(\"metallum_metalfx_color_transfer\")"));
        assertTrue(source.contains("private func metal3MetalFxColorTransfer("));
        assertTrue(source.contains("let lease = metal4MainLease(commandBufferPointer)"));
        assertTrue(source.contains("encodeMetal4Compute("));
        assertTrue(source.contains("static var colorTransferPipeline: MTLComputePipelineState?"));
        assertTrue(source.contains("residencyTrackReleased(NativeState.colorTransferPipeline)"));
    }

    @Test
    void presenterDoesNotRequireSceneAndUiToSharePixelFormat() throws Exception {
        String source = nativeSource();
        assertTrue(source.contains("private var uiFormat: MTLPixelFormat"));
        assertTrue(source.contains("pixelFormat: uiFormat"));
        assertTrue(source.contains("uiColor.pixelFormat != uiFormat"));
        assertTrue(source.contains("sceneColor.pixelFormat == nativeSceneColor.pixelFormat"));
        assertFalse(source.contains("sceneColor.pixelFormat == uiColor.pixelFormat"));
        assertFalse(source.contains("nativeSceneColor.pixelFormat == uiColor.pixelFormat"));
    }
}
''')

print("patched MetalFX color transfer foundation")
