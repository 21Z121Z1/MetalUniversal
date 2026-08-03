# Metal 4 migration macOS 26 CI receipt

- Conclusion: **FAIL**
- Source commit: `2f6662d3628e70aaedf4724900ba70218f4ac52f`
- Workflow trigger: `ca55a02c0d10369241bfb2300fda0a93d05b2ad8`
- Runner: `macOS / ARM64`
- Xcode path: `/Applications/Xcode_26.6.0.app/Contents/Developer`
- Swift: `Apple Swift version 6.3.3 (swiftlang-6.3.3.1.3 clang-2100.1.1.101)`
- SDK: `/Applications/Xcode_26.6.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX26.5.sdk`
- Command: `./gradlew buildMacNative build -x metalFrameGenerationPresentationValidation -x metalFxOffscreenValidation`

## Log tail

```text
Build log was not created; setup failed before Gradle execution.
```
