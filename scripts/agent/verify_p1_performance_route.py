#!/usr/bin/env python3
"""Static proof that the V1 P1 lane reaches runClient with the intended Metal4 toggles."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = (ROOT / "build.gradle").read_text(encoding="utf-8")
RUNNER = (ROOT / "scripts/agent/run_metal4_main_p1_physical_performance.sh").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"P1 performance route invariant failed: {message}")


pilot_default = BUILD.find('systemProperty "metallum.opt.metal4MainQueuePilot", "true"')
forward_loop = BUILD.find('if (key.toString().startsWith("metallum.opt."))', pilot_default)
require(pilot_default >= 0, "native render-efficiency default main-queue pilot assignment disappeared")
require(forward_loop > pilot_default,
        "explicit metallum.opt.* overrides are no longer forwarded after native benchmark defaults")
require('"-Dmetallum.opt.metal4MainQueuePilot=false"' in RUNNER,
        "P1 V1 runner does not explicitly override the legacy main-queue pilot")
require('"-Dmetallum.opt.metal4MainRenderer=false"' in RUNNER,
        "P1 baseline main-renderer toggle is missing")
require('"-Dmetallum.opt.metal4MainRenderer=true"' in RUNNER,
        "P1 candidate main-renderer toggle is missing")
require('"-Dmetallum.opt.metal4=true"' in RUNNER,
        "P1 V1 runner does not request Metal 4")
require('"-Dmetallum.opt.metal4Compiler=true"' in RUNNER,
        "P1 V1 runner does not keep the Metal 4 compiler common")
require('"-Dmetallum.opt.metal4Present=true"' in RUNNER,
        "P1 V1 runner does not keep Metal 4 presentation common")
require('"-Dmetallum.opt.residencySet=true"' in RUNNER,
        "P1 V1 runner does not keep explicit residency common")

print("P1 performance route verification: PASS")
