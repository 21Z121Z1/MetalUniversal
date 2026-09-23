#!/usr/bin/env bash
# Source this only from an explicitly invoked physical validation script.
# No hosted runner or inherited Java agent/override may masquerade as physical.
set -euo pipefail
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo 'Physical validation requires Apple Silicon macOS.' >&2; exit 2
fi
if [[ "${RUNNER_ENVIRONMENT:-}" == github-hosted || -n "${METALLUM_HOSTED_METAL_OFFSCREEN+x}" ]]; then
  echo 'Hosted/offscreen mode is forbidden for physical evidence.' >&2; exit 2
fi
if [[ -n "${JAVA_TOOL_OPTIONS:-}${JDK_JAVA_OPTIONS:-}${_JAVA_OPTIONS:-}" ]]; then
  echo 'Unset inherited Java option variables; physical arguments must come from the audited script.' >&2; exit 2
fi
if (( $(sw_vers -productVersion | cut -d. -f1) < 26 )); then
  echo 'The Metal 4 physical acceptance protocol requires macOS 26 or newer.' >&2; exit 2
fi
if [[ "$(stat -f %u /dev/console)" != "$(id -u)" ]]; then
  echo 'Run as the active logged-in console user, not a launch daemon or SSH-only session.' >&2; exit 2
fi
launchctl print "gui/$(id -u)" >/dev/null
xcode_version="$(xcodebuild -version)"
printf '%s\n' "$xcode_version"
xcode_major="$(printf '%s\n' "$xcode_version" | awk '/^Xcode / {split($2,v,"."); print v[1]; exit}')"
sdk_major="$(xcrun --sdk macosx --show-sdk-version | cut -d. -f1)"
java_version="$(java -XshowSettings:properties -version 2>&1)"
printf '%s\n' "$java_version"
java_major="$(printf '%s\n' "$java_version" | awk '/^[[:space:]]*java.specification.version = / {print $3; exit}')"
if [[ ! "$xcode_major" =~ ^[0-9]+$ || ! "$sdk_major" =~ ^[0-9]+$ || ! "$java_major" =~ ^[0-9]+$ ]]; then
  echo 'Cannot identify the selected Xcode/macOS SDK/JDK toolchain.' >&2; exit 2
fi
if (( xcode_major < 26 || sdk_major < 26 || java_major != 25 )); then
  echo 'Physical acceptance requires selected Xcode/macOS SDK 26+ and JDK 25.' >&2; exit 2
fi
python3 --version
# Probe an unlocked foreground GUI session and a non-paravirtual Apple GPU.
xcrun swift - <<'SWIFT'
import Foundation
import CoreGraphics
import Metal
guard let session = CGSessionCopyCurrentDictionary() as? [String: Any],
      session[kCGSessionOnConsoleKey as String] as? Bool == true,
      session["CGSSessionScreenIsLocked"] as? Bool != true,
      let device = MTLCreateSystemDefaultDevice(),
      !device.name.lowercased().contains("paravirtual"),
      device.name.lowercased().contains("apple") else {
    fputs("Physical preflight requires an unlocked WindowServer console and a physical Apple GPU.\n", stderr)
    exit(2)
}
print("PHYSICAL_CONSOLE_PREFLIGHT_PASS \(device.name)")
SWIFT
