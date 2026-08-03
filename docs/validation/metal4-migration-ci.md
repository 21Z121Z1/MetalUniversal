# Metal 4 migration macOS 26 CI receipt

- Conclusion: **FAIL**
- Source commit: `ec3efbbedc3b6240a6d46760eb0a74dce8a72084`
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

[Incubating] Problems report is available at: file:///Users/runner/work/MetalUniversal/MetalUniversal/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* Where:
Build file '/Users/runner/work/MetalUniversal/MetalUniversal/build.gradle' line: 391

* What went wrong:
Could not compile build file '/Users/runner/work/MetalUniversal/MetalUniversal/build.gradle'.
> startup failed:
  build file '/Users/runner/work/MetalUniversal/MetalUniversal/build.gradle': 391: token recognition error at: '(' @ line 391, column 65.
     run swiftc -typecheck -sdk \"$(xcrun --s
                                   ^
  
  1 error


* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 7s
```
