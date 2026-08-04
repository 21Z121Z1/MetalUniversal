# Metal 4 device-session macOS 26 CI receipt

- Conclusion: **PASS**
- Source commit: `43af5008c5b60b0eb3aa70193b5dca8f1740f814`
- Workflow trigger: `d67bfe921c7312c53c555ca08781eb4409ba40ad`
- Runner: `macOS / ARM64`
- Xcode path: `/Applications/Xcode_26.6.0.app/Contents/Developer`
- Swift: `Apple Swift version 6.3.3 (swiftlang-6.3.3.1.3 clang-2100.1.1.101)`
- SDK: `/Applications/Xcode_26.6.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX26.5.sdk`
- Patch status: `0`
- Build status: `0`
- Source-clean status: `0`
- Command: `./gradlew buildMacNative build -x metalFrameGenerationPresentationValidation -x metalFxOffscreenValidation`

## Patch log

```text
/Users/runner/work/MetalUniversal/MetalUniversal/scripts/apply_metal4_device_session_fix.py:28: SyntaxWarning: "\g" is an invalid escape sequence. Such sequences will not work in the future. Did you mean "\\g"? A raw string is also an option.
  """\g<i>if let existing = metal4CompilerStorage as? MTL4Compiler {
```

## Build log tail

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

> Task :buildMacNative
src/main/native/MetallumNative.swift:2404:19: warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 1446 | 
 1447 | @available(macOS 26.0, *)
 1448 | final class MetalFrameGenerationPresenter: NSObject, CAMetalDisplayLinkDelegate {
      |             `- note: enclosing scope here
 1449 |     private struct PendingFrame {
 1450 |         let sourceFrameID: UInt64
      :
 2402 |         }()
 2403 |         if let lease = metal4Lease {
 2404 |             guard #available(macOS 26.0, *),
      |                   `- warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 2405 |                   let copies = lease.commandBuffer.makeComputeCommandEncoder() else {
 2406 |                 completeFrame()

src/main/native/MetallumNative.swift:6079:12: warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6000 | #if os(macOS) && canImport(MetalFX)
 6001 | @available(macOS 26.0, iOS 26.0, *)
 6002 | private func metal4MetalFxEncodeV2(
      |              `- note: enclosing scope here
 6003 |     lease: Metal4MainCommandBufferLease, device: MTLDevice,
 6004 |     colorTexture: MTLTexture, depthTexture: MTLTexture, handDepthTexture: MTLTexture?,
      :
 6077 |         descriptor.isAutoExposureEnabled = false
 6078 |         descriptor.requiresSynchronousInitialization = true
 6079 |         if #available(macOS 14.4, *) {
      |            `- warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6080 |             descriptor.isReactiveMaskTextureEnabled = true
 6081 |             descriptor.reactiveMaskTextureFormat = reactiveTexture.pixelFormat

src/main/native/MetallumNative.swift:6220:8: warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6000 | #if os(macOS) && canImport(MetalFX)
 6001 | @available(macOS 26.0, iOS 26.0, *)
 6002 | private func metal4MetalFxEncodeV2(
      |              `- note: enclosing scope here
 6003 |     lease: Metal4MainCommandBufferLease, device: MTLDevice,
 6004 |     colorTexture: MTLTexture, depthTexture: MTLTexture, handDepthTexture: MTLTexture?,
      :
 6218 |     scaler.reset = reset != 0
 6219 |     scaler.isDepthReversed = depthReversed != 0
 6220 |     if #available(macOS 14.4, *) { scaler.reactiveMaskTexture = reactiveTexture }
      |        `- warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6221 |     scaler.fence = fence
 6222 |     lease.commandBuffer.pushDebugGroup("MetalFX Temporal Upscale V2 (Metal 4)")

src/main/native/MetallumNative.swift:6726:16: warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6697 | ) -> Int32 {
 6698 |     #if os(macOS) && canImport(MetalFX)
 6699 |     if #available(macOS 26.0, *) {
      |     `- note: enclosing scope here
 6700 |         return autoreleasepool {
 6701 |             let presenter: MetalFrameGenerationPresenter
      :
 6724 |             }
 6725 | 
 6726 |             if #available(macOS 26.0, *), let lease = metal4MainLease(commandBufferPointer) {
      |                `- warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6727 |                 lease.commandBuffer.pushDebugGroup("MetalFX Frame Generation Inputs (Metal 4)")
 6728 |             } else {

src/main/native/MetallumNative.swift:6750:16: warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6697 | ) -> Int32 {
 6698 |     #if os(macOS) && canImport(MetalFX)
 6699 |     if #available(macOS 26.0, *) {
      |     `- note: enclosing scope here
 6700 |         return autoreleasepool {
 6701 |             let presenter: MetalFrameGenerationPresenter
      :
 6748 |                 globalFence: globalFence
 6749 |             )
 6750 |             if #available(macOS 26.0, *), let lease = metal4MainLease(commandBufferPointer) {
      |                `- warning: unnecessary check for 'macOS'; enclosing scope ensures guard will always be true
 6751 |                 lease.commandBuffer.popDebugGroup()
 6752 |             } else {

src/main/native/MetallumNative.swift:6131:13: warning: variable 'cameraUniforms' was never mutated; consider changing to 'let' constant
 6129 |     }
 6130 |     if NativeState.legacyMotionPasses {
 6131 |         var cameraUniforms = MotionUniforms(
      |             `- warning: variable 'cameraUniforms' was never mutated; consider changing to 'let' constant
 6132 |             currentViewProjection: currentMatrix,
 6133 |             inverseCurrentViewProjection: inverseMatrix,

> Task :compileJava

> Task :buildIOSNative
clang: warning: using sysroot for 'MacOSX' but targeting 'iPhone' [-Wincompatible-sysroot]

> Task :buildIOSSpvc
[buildIOSSpvc] Running: git clone --depth 1 --branch vulkan-sdk-1.3.290.0 https://github.com/KhronosGroup/SPIRV-Cross.git /Users/runner/work/MetalUniversal/MetalUniversal/build/spirv-cross-src
[buildIOSSpvc] Cloning into '/Users/runner/work/MetalUniversal/MetalUniversal/build/spirv-cross-src'...
[buildIOSSpvc] Running: cmake -G Unix Makefiles -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_DEPLOYMENT_TARGET=14.0 -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_MAKE_PROGRAM=/usr/bin/make -DSPIRV_CROSS_SHARED=ON -DSPIRV_CROSS_STATIC=OFF -DSPIRV_CROSS_CLI=OFF -DSPIRV_CROSS_ENABLE_TESTS=OFF -DSPIRV_CROSS_ENABLE_C_API=ON -DSPIRV_CROSS_ENABLE_MSL=ON -DSPIRV_CROSS_ENABLE_GLSL=ON -DSPIRV_CROSS_ENABLE_HLSL=OFF -DSPIRV_CROSS_ENABLE_CPP=OFF -DSPIRV_CROSS_ENABLE_REFLECT=OFF -DSPIRV_CROSS_ENABLE_UTIL=OFF -DCMAKE_BUILD_TYPE=Release /Users/runner/work/MetalUniversal/MetalUniversal/build/spirv-cross-src
[buildIOSSpvc] CMake Warning (deprecated) at CMakeLists.txt:22 (cmake_minimum_required):
[buildIOSSpvc]   Compatibility with CMake < 3.10 will be removed from a future version of
[buildIOSSpvc]   CMake.
[buildIOSSpvc] 
[buildIOSSpvc]   Update the VERSION argument <min> value.  Or, use the <min>...<max> syntax
[buildIOSSpvc]   to tell CMake that the project requires at least <min> but has been updated
[buildIOSSpvc]   to work with policies introduced by <max> or earlier.
[buildIOSSpvc] This warning is for project developers.  Use -Wno-author or -Wno-deprecated
[buildIOSSpvc] to suppress it.
[buildIOSSpvc] 
[buildIOSSpvc] -- The CXX compiler identification is AppleClang 21.0.0.21000101
[buildIOSSpvc] -- The C compiler identification is AppleClang 21.0.0.21000101
[buildIOSSpvc] -- Detecting CXX compiler ABI info
[buildIOSSpvc] -- Detecting CXX compiler ABI info - done
[buildIOSSpvc] -- Check for working CXX compiler: /usr/bin/c++ - skipped
[buildIOSSpvc] -- Detecting CXX compile features
[buildIOSSpvc] -- Detecting CXX compile features - done
[buildIOSSpvc] -- Detecting C compiler ABI info
[buildIOSSpvc] -- Detecting C compiler ABI info - done
[buildIOSSpvc] -- Check for working C compiler: /usr/bin/cc - skipped
[buildIOSSpvc] -- Detecting C compile features
[buildIOSSpvc] -- Detecting C compile features - done
[buildIOSSpvc] -- SPIRV-Cross: Finding Git version for SPIRV-Cross.
[buildIOSSpvc] -- Found Git: /opt/homebrew/bin/git (found version "2.55.0")
[buildIOSSpvc] -- SPIRV-Cross: Git hash: vulkan-sdk-1.3.290.0
[buildIOSSpvc] -- Configuring done (5.2s)
[buildIOSSpvc] -- Generating done (0.0s)
[buildIOSSpvc] -- Build files have been written to: /Users/runner/work/MetalUniversal/MetalUniversal/build/spirv-cross-build
[buildIOSSpvc] Running: cmake --build . --config Release -j 4
[buildIOSSpvc] [ 12%] Building CXX object CMakeFiles/spirv-cross-c-shared.dir/spirv_cross.cpp.o
[buildIOSSpvc] [ 25%] Building CXX object CMakeFiles/spirv-cross-c-shared.dir/spirv_parser.cpp.o
[buildIOSSpvc] [ 37%] Building CXX object CMakeFiles/spirv-cross-c-shared.dir/spirv_cross_parsed_ir.cpp.o
[buildIOSSpvc] [ 50%] Building CXX object CMakeFiles/spirv-cross-c-shared.dir/spirv_cfg.cpp.o
[buildIOSSpvc] [ 62%] Building CXX object CMakeFiles/spirv-cross-c-shared.dir/spirv_cross_c.cpp.o
[buildIOSSpvc] [ 75%] Building CXX object CMakeFiles/spirv-cross-c-shared.dir/spirv_glsl.cpp.o
[buildIOSSpvc] [ 87%] Building CXX object CMakeFiles/spirv-cross-c-shared.dir/spirv_msl.cpp.o
[buildIOSSpvc] [100%] Linking CXX shared library libspirv-cross-c-shared.dylib
[buildIOSSpvc] [100%] Built target spirv-cross-c-shared
[buildIOSSpvc] Built libspvc.dylib -> /Users/runner/work/MetalUniversal/MetalUniversal/src/main/resources/natives/ios/libspvc.dylib
[buildIOSSpvc] MSL backend symbols present: true
[buildIOSSpvc] Architecture: Non-fat file: /Users/runner/work/MetalUniversal/MetalUniversal/build/spirv-cross-build/libspirv-cross-c-shared.dylib is architecture: arm64
[buildIOSSpvc] LC_BUILD_VERSION platform: iOS

> Task :processResources
> Task :classes
> Task :processIncludeJars
> Task :sourcesJar
> Task :metal4ApiProbe
> Task :compileTestJava
> Task :extractIrisNestedJars
> Task :processTestResources
> Task :testClasses
> Task :metalComputeBackendIntegrationTest SKIPPED
> Task :compileMetalFrameGenerationLifecycleTest

> Task :metalFrameGenerationLifecycleTest
PASS: display-aware source admission
PASS: generated then real
PASS: GUI suspend and resize
PASS: enqueue then shutdown
PASS: newer source supersedes stalled source
PASS: stale callback preserves newer history
PASS: generated submitted shutdown
PASS: real submitted shutdown
PASS: command buffer failure
PASS: stale display update
PASS: duplicate callback and idempotent release
PASS: presentedTime zero
PASS: presented before GPU completion
Metal frame-generation lifecycle tests passed: 13

> Task :compileMetalFxPerformanceValidation
> Task :metalFxPerformanceValidation SKIPPED
> Task :metalIrisTargetsIntegrationTest SKIPPED
> Task :metalMrtBackendIntegrationTest SKIPPED
> Task :compileMetalMrtSmokeTest
> Task :metalMrtSmokeTest SKIPPED
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.joml.MemUtil$MemUtilUnsafe (file:/Users/runner/.gradle/caches/modules-2/files-2.1/org.joml/joml/1.10.8/fc0a71dad90a2cf41d82a76156a0e700af8e4f8d/joml-1.10.8.jar)
WARNING: Please consider reporting this to the maintainers of class org.joml.MemUtil$MemUtilUnsafe
WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
> Task :test
> Task :validateAccessWidener

> Task :verifyIsolatedClientProfiles
Isolated client profiles: PASS

> Task :check
> Task :jar
> Task :assemble
> Task :build

[Incubating] Problems report is available at: file:///Users/runner/work/MetalUniversal/MetalUniversal/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/9.4.1/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 1m 58s
19 actionable tasks: 19 executed
```
