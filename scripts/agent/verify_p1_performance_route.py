#!/usr/bin/env python3
"""Static proof that P1 benchmark toggles reach the actual Minecraft client JVM."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = (ROOT / "build.gradle").read_text(encoding="utf-8")
RUNNER = (ROOT / "scripts/agent/run_metal4_main_p1_physical_performance.sh").read_text(encoding="utf-8")
MATRIX = (ROOT / "scripts/agent/run_metal4_main_p1_physical_matrix.sh").read_text(encoding="utf-8")
IRIS_COMPAT = (ROOT / "src/main/java/com/metallum/client/metal/render/MetalIrisCompat.java").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"P1 performance route invariant failed: {message}")


pilot_default = BUILD.find('systemProperty "metallum.opt.metal4MainQueuePilot", "true"')
forward_loop = BUILD.find('if (key.toString().startsWith("metallum.opt."))', pilot_default)
require(pilot_default >= 0, "native render-efficiency default main-queue pilot assignment disappeared")
require(forward_loop > pilot_default,
        "explicit metallum.opt.* overrides are no longer forwarded after native benchmark defaults")
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
# defaults false. build.gradle only forwards metallum.opt.* generically, so the
# physical matrix must inject the semantic property into the actual Java
# runtime rather than assuming a Gradle -D reaches Loom's child JVM.
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

# The matrix also proves that the performance worktree rebuilt to the exact
# production JAR/native hashes that already passed the physical correctness pair.
require('LOCAL_JAR_SHA' in MATRIX and 'CORRECTNESS_JAR_SHA' in MATRIX,
        "matrix does not bind performance to the correctness-approved production JAR")
require('LOCAL_DYLIB_SHA' in MATRIX and 'CORRECTNESS_DYLIB_SHA' in MATRIX,
        "matrix does not bind performance to the correctness-approved native dylib")

print("P1 performance route verification: PASS")
