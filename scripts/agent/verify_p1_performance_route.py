#!/usr/bin/env python3
"""Verify that P1 benchmark properties reach the Minecraft client JVM."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
BUILD = (ROOT / "build.gradle").read_text(encoding="utf-8")
RUNNER = (ROOT / "scripts/agent/run_metal4_main_p1_physical_performance.sh").read_text(encoding="utf-8")
MATRIX = (ROOT / "scripts/agent/run_metal4_main_p1_physical_matrix.sh").read_text(encoding="utf-8")
IRIS_COMPAT = (ROOT / "src/main/java/com/metallum/client/metal/render/MetalIrisCompat.java").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"P1 performance route invariant failed: {message}")


def closure_block(source: str, opening_brace: int) -> str:
    """Return one balanced Groovy closure block, including both braces."""
    require(opening_brace >= 0 and source[opening_brace] == "{",
            "could not locate the property-forwarding closure")
    depth = 0
    quote = None
    escaped = False
    for index in range(opening_brace, len(source)):
        char = source[index]
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in {"'", '"'}:
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[opening_brace:index + 1]
    raise SystemExit("P1 performance route invariant failed: unterminated property-forwarding closure")


pilot_default = BUILD.find('systemProperty "metallum.opt.metal4MainQueuePilot", "true"')
require(pilot_default >= 0, "native render-efficiency default main-queue pilot assignment disappeared")

forward_match = re.search(
    r"System\.properties\.each\s*\{\s*key\s*,\s*value\s*->",
    BUILD[pilot_default:],
)
require(forward_match is not None,
        "the client JVM no longer has a generic system-property forwarding block")
forward_start = pilot_default + forward_match.start()
forward_open = BUILD.find("{", forward_start)
forward_block = closure_block(BUILD, forward_open)
normalized_forward_block = re.sub(r"\s+", "", forward_block)
require('startsWith("metallum.opt.")' in forward_block,
        "explicit metallum.opt.* overrides are not admitted by the forwarding block")
require('startsWith("metallum.terrain.")' in forward_block,
        "explicit metallum.terrain.* overrides are not admitted by the forwarding block")
require('systemPropertykey.toString(),value.toString()' in normalized_forward_block,
        "admitted system properties are not forwarded to the client JVM")

require('"-Dmetallum.opt.metal4MainQueuePilot=false"' in RUNNER,
        "P1 runner does not explicitly override the legacy main-queue pilot")
require('"-Dmetallum.opt.metal4MainRenderer=false"' in RUNNER,
        "P1 baseline main-renderer toggle is missing")
require('"-Dmetallum.opt.metal4MainRenderer=true"' in RUNNER,
        "P1 candidate main-renderer toggle is missing")
require('"-Dmetallum.opt.metal4=true"' in RUNNER,
        "P1 runner does not request Metal 4")
require('"-Dmetallum.opt.metal4Compiler=true"' in RUNNER,
        "P1 runner does not keep the Metal 4 compiler common")
require('"-Dmetallum.opt.metal4Present=true"' in RUNNER,
        "P1 runner does not keep Metal 4 presentation common")
require('"-Dmetallum.opt.residencySet=true"' in RUNNER,
        "P1 runner does not keep explicit residency common")

# The Iris semantic gate is a static startup property in the product and
# defaults false. The physical matrix must inject it into the actual Java
# runtime rather than assuming that a Gradle -D option reaches Loom's child JVM.
require('System.getProperty("metallum.iris.semantic", "false")' in IRIS_COMPAT,
        "Iris semantic startup gate no longer has the expected fail-closed product contract")
require('JAVA_TOOL_OPTIONS="$p1_java_tool_options"' in MATRIX,
        "physical matrix does not inject semantic state into the Minecraft JVM")
require('-Dmetallum.iris.semantic=$semantic' in MATRIX,
        "physical matrix does not set the product Iris semantic startup property")
require('semantic=false' in MATRIX and 'semantic=true' in MATRIX,
        "physical matrix does not distinguish V1 from I0/I1 semantic state")
require('grep -F "Iris-on-Metal semantic layer active:"' in MATRIX,
        "I0/I1 matrix does not prove semantic-layer activation in every trial")
require('grep -F "Using shaderpack: $STAGED_PACK_NAME"' in RUNNER,
        "I0/I1 profile runner does not prove the exact staged shader pack")

# Performance evidence must use the same product binaries that passed the
# paired physical correctness run.
require('LOCAL_JAR_SHA' in MATRIX and 'CORRECTNESS_JAR_SHA' in MATRIX,
        "matrix does not bind performance to the correctness-approved production JAR")
require('LOCAL_DYLIB_SHA' in MATRIX and 'CORRECTNESS_DYLIB_SHA' in MATRIX,
        "matrix does not bind performance to the correctness-approved native dylib")

print("P1 performance route verification: PASS")
