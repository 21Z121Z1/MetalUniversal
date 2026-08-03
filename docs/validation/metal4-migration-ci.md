# Metal 4 migration macOS 26 CI receipt

- Conclusion: **FAIL**
- Source commit: `0594662c3b18279e598b3fa4f9bdc48f56ba3b46`
- Workflow trigger: `a841e534bf77a487df3b0b4cb94686ed9116d0f1`
- Runner: `macOS / ARM64`
- Xcode path: `/Applications/Xcode_26.6.0.app/Contents/Developer`
- Swift: `Apple Swift version 6.3.3 (swiftlang-6.3.3.1.3 clang-2100.1.1.101)`
- SDK: `/Applications/Xcode_26.6.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX26.5.sdk`
- Command: `./gradlew buildMacNative build -x metalFrameGenerationPresentationValidation -x metalFxOffscreenValidation`

## Log tail

```text
Downloading https://services.gradle.org/distributions/gradle-9.4.1-bin.zip
.............10%.............20%.............30%.............40%.............50%.............60%..............70%.............80%.............90%.............100%

Welcome to Gradle 9.4.1!

Here are the highlights of this release:
 - Java 26 support
 - Non-class-based JVM tests
 - Enhanced console progress bar

For more details see https://docs.gradle.org/9.4.1/release-notes.html

Starting a Gradle Daemon (subsequent builds will be faster)

> Configure project :
Fabric Loom: 1.16.3

> Task :buildMacNative FAILED
src/main/native/MetalFrameGenerationLifecycle.swift:350:6: error: invalid redeclaration of 'metallum_MTLComputeCommandEncoder_setComputePipelineState'
348 | }
349 | 
350 | func metallum_MTLComputeCommandEncoder_setComputePipelineState(
    |      `- error: invalid redeclaration of 'metallum_MTLComputeCommandEncoder_setComputePipelineState'
351 |     _ pointer: UnsafeMutableRawPointer,
352 |     _ pipelineState: MTLComputePipelineState

src/main/native/MetalFrameGenerationLifecycle.swift:370:6: error: invalid redeclaration of 'metallum_MTLComputeCommandEncoder_setTexture'
368 | }
369 | 
370 | func metallum_MTLComputeCommandEncoder_setTexture(
    |      `- error: invalid redeclaration of 'metallum_MTLComputeCommandEncoder_setTexture'
371 |     _ pointer: UnsafeMutableRawPointer,
372 |     _ texture: MTLTexture?,

src/main/native/MetalFrameGenerationLifecycle.swift:378:6: error: invalid redeclaration of 'metallum_MTLComputeCommandEncoder_setSamplerState'
376 | }
377 | 
378 | func metallum_MTLComputeCommandEncoder_setSamplerState(
    |      `- error: invalid redeclaration of 'metallum_MTLComputeCommandEncoder_setSamplerState'
379 |     _ pointer: UnsafeMutableRawPointer,
380 |     _ sampler: MTLSamplerState?,

src/main/native/MetalFrameGenerationLifecycle.swift:386:6: error: invalid redeclaration of 'metallum_MTLComputeCommandEncoder_dispatchThreadgroups'
384 | }
385 | 
386 | func metallum_MTLComputeCommandEncoder_dispatchThreadgroups(
    |      `- error: invalid redeclaration of 'metallum_MTLComputeCommandEncoder_dispatchThreadgroups'
387 |     _ pointer: UnsafeMutableRawPointer,
388 |     _ groupsX: Int32,

src/main/native/MetallumNative.swift:2450:19: warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 1492 | 
 1493 | @available(macOS 26.0, *)
 1494 | final class MetalFrameGenerationPresenter: NSObject, CAMetalDisplayLinkDelegate {
      |             `- note: enclosing scope here
 1495 |     private struct PendingFrame {
 1496 |         let sourceFrameID: UInt64
      :
 2448 |         }()
 2449 |         if let lease = metal4Lease {
 2450 |             guard #available(macOS 26.0, *),
      |                   `- warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 2451 |                   let copies = lease.commandBuffer.makeComputeCommandEncoder() else {
 2452 |                 completeFrame()

src/main/native/MetallumNative.swift:6125:12: warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6046 | #if os(macOS) && canImport(MetalFX)
 6047 | @available(macOS 26.0, iOS 26.0, *)
 6048 | private func metal4MetalFxEncodeV2(
      |              `- note: enclosing scope here
 6049 |     lease: Metal4MainCommandBufferLease, device: MTLDevice,
 6050 |     colorTexture: MTLTexture, depthTexture: MTLTexture, handDepthTexture: MTLTexture?,
      :
 6123 |         descriptor.isAutoExposureEnabled = false
 6124 |         descriptor.requiresSynchronousInitialization = true
 6125 |         if #available(macOS 14.4, *) {
      |            `- warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6126 |             descriptor.isReactiveMaskTextureEnabled = true
 6127 |             descriptor.reactiveMaskTextureFormat = reactiveTexture.pixelFormat

src/main/native/MetallumNative.swift:6266:8: warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6046 | #if os(macOS) && canImport(MetalFX)
 6047 | @available(macOS 26.0, iOS 26.0, *)
 6048 | private func metal4MetalFxEncodeV2(
      |              `- note: enclosing scope here
 6049 |     lease: Metal4MainCommandBufferLease, device: MTLDevice,
 6050 |     colorTexture: MTLTexture, depthTexture: MTLTexture, handDepthTexture: MTLTexture?,
      :
 6264 |     scaler.reset = reset != 0
 6265 |     scaler.isDepthReversed = depthReversed != 0
 6266 |     if #available(macOS 14.4, *) { scaler.reactiveMaskTexture = reactiveTexture }
      |        `- warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6267 |     scaler.fence = fence
 6268 |     lease.commandBuffer.pushDebugGroup("MetalFX Temporal Upscale V2 (Metal 4)")

src/main/native/MetallumNative.swift:6772:16: warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6743 | ) -> Int32 {
 6744 |     #if os(macOS) && canImport(MetalFX)
 6745 |     if #available(macOS 26.0, *) {
      |     `- note: enclosing scope here
 6746 |         return autoreleasepool {
 6747 |             let presenter: MetalFrameGenerationPresenter
      :
 6770 |             }
 6771 | 
 6772 |             if #available(macOS 26.0, *), let lease = metal4MainLease(commandBufferPointer) {
      |                `- warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6773 |                 lease.commandBuffer.pushDebugGroup("MetalFX Frame Generation Inputs (Metal 4)")
 6774 |             } else {

src/main/native/MetallumNative.swift:6796:16: warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6743 | ) -> Int32 {
 6744 |     #if os(macOS) && canImport(MetalFX)
 6745 |     if #available(macOS 26.0, *) {
      |     `- note: enclosing scope here
 6746 |         return autoreleasepool {
 6747 |             let presenter: MetalFrameGenerationPresenter
      :
 6794 |                 globalFence: globalFence
 6795 |             )
 6796 |             if #available(macOS 26.0, *), let lease = metal4MainLease(commandBufferPointer) {
      |                `- warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6797 |                 lease.commandBuffer.popDebugGroup()
 6798 |             } else {

src/main/native/MetallumNative.swift:6177:13: warning: variable 'cameraUniforms' was never mutated; consider changing to 'let' constant
 6175 |     }
 6176 |     if NativeState.legacyMotionPasses {
 6177 |         var cameraUniforms = MotionUniforms(
      |             `- warning: variable 'cameraUniforms' was never mutated; consider changing to 'let' constant
 6178 |             currentViewProjection: currentMatrix,
 6179 |             inverseCurrentViewProjection: inverseMatrix,

[Incubating] Problems report is available at: file:///Users/runner/work/MetalUniversal/MetalUniversal/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':buildMacNative'.
> Process 'command 'swiftc'' finished with non-zero exit value 1

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/9.4.1/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD FAILED in 53s
1 actionable task: 1 executed
```
