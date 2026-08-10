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


def task_span(task_name: str) -> tuple[int, int]:
    marker = f'tasks.register("{task_name}"'
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"task not found: {task_name}")
    next_task = text.find('tasks.register("', start + len(marker))
    return start, len(text) if next_task < 0 else next_task


def retarget_physical_task(task_name: str, replacement: str) -> None:
    global text
    start, end = task_span(task_name)
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


def retarget_presentation_task() -> None:
    global text
    task_name = "metalFrameGenerationPresentationValidation"
    start, end = task_span(task_name)
    block = text[start:end]
    replacement = "presentationMetalValidationAvailable()"
    if replacement in block:
        print(f"{task_name} already uses {replacement}")
        return
    if "physicalMetalValidationAvailable()" in block:
        block = block.replace("physicalMetalValidationAvailable()", replacement, 1)
    elif "!hostedCi" in block:
        block = block.replace("!hostedCi", replacement, 1)
    else:
        raise SystemExit(f"{task_name} has no recognized hosted/physical gate to retarget")
    text = text[:start] + block + text[end:]
    print(f"{task_name}: migrated to {replacement}")


retarget_physical_task("metalMrtSmokeTest", "hostedOffscreenMetalValidationAvailable()")
retarget_physical_task("metalFxOffscreenValidation", "metalFxValidationAvailable()")
retarget_presentation_task()

if "hardwareMetalValidationAvailable" in text:
    raise SystemExit("legacy hardwareMetalValidationAvailable symbol remains")

path.write_text(text)
