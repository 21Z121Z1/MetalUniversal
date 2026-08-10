#!/usr/bin/env python3
from pathlib import Path

path = Path("build.gradle")
text = path.read_text()

old = '''// GitHub's hosted macOS 26 virtual GPU aborts when libMTLHud tries to enable
// encoder counter sampling, even with both Metal validation layers disabled.
// CI still compiles the native backend and GPU harnesses, but executes only
// headless tests which do not create an MTLDevice. Real GPU/readback/window
// validation remains part of every local Apple Silicon `build`.
def hostedCi = "true".equalsIgnoreCase(System.getenv("CI"))
def metalApiValidation = hostedCi ? "0" : "1"
def metalShaderValidation = hostedCi ? "0" : "1"
def hardwareMetalValidationAvailable = {
\torg.gradle.internal.os.OperatingSystem.current().isMacOsX() && !hostedCi
}
'''
new = '''// GitHub's standard Apple Silicon macOS 26 runner exposes a paravirtual Metal
// device and can execute proven offscreen command-buffer/readback workloads.
// Keep capability classes separate: hosted-offscreen is opt-in after a probe,
// while counter-sampling/MetalFX and presentation remain physical-only until
// independently proven safe on the hosted device.
def hostedCi = "true".equalsIgnoreCase(System.getenv("CI"))
def metalApiValidation = hostedCi ? "0" : "1"
def metalShaderValidation = hostedCi ? "0" : "1"
def isMacOSHost = org.gradle.internal.os.OperatingSystem.current().isMacOsX()
def hostedOffscreenMetalValidationAvailable = {
\tisMacOSHost && (!hostedCi || "true".equalsIgnoreCase(System.getenv("METALLUM_HOSTED_METAL_OFFSCREEN")))
}
def physicalMetalValidationAvailable = {
\tisMacOSHost && !hostedCi
}
def metalFxValidationAvailable = {
\tphysicalMetalValidationAvailable()
}
def presentationMetalValidationAvailable = {
\tphysicalMetalValidationAvailable()
}
'''
if old not in text:
    if new in text:
        print("capability gate definitions already applied")
    else:
        raise SystemExit("expected legacy hosted-CI gate block not found")
else:
    text = text.replace(old, new, 1)

legacy_calls = text.count("hardwareMetalValidationAvailable()")
if legacy_calls:
    text = text.replace("hardwareMetalValidationAvailable()", "physicalMetalValidationAvailable()")
    print(f"migrated {legacy_calls} legacy hardware gate call(s) to physical gate")


def retarget_task(task_name: str, replacement: str) -> None:
    global text
    marker = f'tasks.register("{task_name}"'
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"task not found: {task_name}")
    next_task = text.find('tasks.register("', start + len(marker))
    end = len(text) if next_task < 0 else next_task
    block = text[start:end]
    needle = "physicalMetalValidationAvailable()"
    if replacement in block:
        print(f"{task_name} already uses {replacement}")
        return
    if needle not in block:
        raise SystemExit(f"{task_name} has no physical gate to retarget")
    block = block.replace(needle, replacement, 1)
    text = text[:start] + block + text[end:]
    print(f"{task_name}: {needle} -> {replacement}")


retarget_task("metalMrtSmokeTest", "hostedOffscreenMetalValidationAvailable()")
retarget_task("metalFxOffscreenValidation", "metalFxValidationAvailable()")
retarget_task("metalFrameGenerationPresentationValidation", "presentationMetalValidationAvailable()")

if "hardwareMetalValidationAvailable" in text:
    raise SystemExit("legacy hardwareMetalValidationAvailable symbol remains")

path.write_text(text)
