#!/usr/bin/env python3
"""Static launch-contract regression checks; not physical runtime evidence."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
build = (ROOT / '.github/ci/minecraft-e2e/build.gradle').read_text()
runner = (ROOT / 'scripts/agent/run_metal4_main_p1_physical_performance.sh').read_text()
physical = build.split('tasks.register("runProductionClientValidation"', 1)[1]
assert 'mods.from(metallumJarProvider)' in physical
assert 'requireProductionArtifact' in physical
assert 'minecraftNativeRenderEfficiencyValidation' not in runner
assert 'runProductionClientValidation' in runner
assert 'verify_loaded_artifact.py' in runner
assert 'correctness gate belongs to a different renderer stack' in runner
assert '-Dmetallum.opt.metal4MainQueuePilot=false' in physical
assert '-Dfabric.client.gametest' not in physical
assert 'physical_preflight' in runner
print('production P1 launch contract: PASS (static only)')
