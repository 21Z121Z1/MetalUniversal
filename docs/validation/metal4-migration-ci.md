# Metal 4 migration macOS 26 CI receipt

- Conclusion: **FAIL**
- Source commit: `4329d2318115e4da84607c6d6a66c7c15f332ffa`
- Workflow trigger: `4329d2318115e4da84607c6d6a66c7c15f332ffa`
- Runner: `macOS / ARM64`
- Xcode path: `/Applications/Xcode_26.6.app/Contents/Developer`
- Swift: `Apple Swift version 6.3.3 (swiftlang-6.3.3.1.3 clang-2100.1.1.101)`
- SDK: `/Applications/Xcode_26.6.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX26.5.sdk`
- Build status: `setup-failed`
- Source-clean status: `0`
- Command: `./gradlew buildMacNative build -x metalFrameGenerationPresentationValidation -x metalFxOffscreenValidation`

## Log tail

```text
Build log was not created; setup failed before Gradle execution.
```
