#!/usr/bin/env python3
"""Import reviewed renderer/validation modules onto the exact upstream base.
This helper stays on the workbench branch and is not part of the upstream PR.
"""
import subprocess
from pathlib import Path
SOURCE = '790f9cb2a4c7195158e7b2f71a030fcbf8db43e1'
BASE = 'f8294b2fb6ce2edc56d418510294111c9276091e'
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() == BASE
prefixes = [
 'src/main/java/com/metallum/Metallum.java',
 'src/main/java/com/metallum/client/metal/',
 'src/main/native/',
 'src/main/java/com/metallum/mixin/',
 'src/main/java/com/metallum/client/terrain/',
 'src/main/java/com/metallum/client/validation/',
 'src/main/resources/assets/metallum/shaders/',
 'src/main/resources/assets/metallum/lang/',
 'src/test/', 'src/validation/', 'validation/',
 '.github/ci/minecraft-e2e/',
 '.github/ci/HostedMetalGradle.init.gradle',
 '.github/ci/HostedMetalCapabilityProbe.swift',
 '.github/ci/JvmPsoThreadingProbe.java',
 'scripts/agent/', 'build.gradle', 'src/main/resources/metallum.mixins.json'
]
excluded = {
 'scripts/agent/extract_minecraft_motion_reference.py',
 'scripts/agent/record_minecraft_ownership.py',
 'scripts/agent/record_sdk_contract.py',
 'scripts/agent/verify_minecraft_terrain_reference.py',
 'scripts/agent/run_iris_perf_cycle.sh'
}
paths = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', SOURCE], text=True).splitlines()
for path in sorted(paths):
 if Path(path).name == 'AGENTS.md' or path in excluded:
  continue
 if any(path == prefix or (prefix.endswith('/') and path.startswith(prefix)) for prefix in prefixes):
  subprocess.run(['git', 'restore', '--source=' + SOURCE, '--staged', '--worktree', '--', path], check=True)
contracts = [
 'unified-evaluation-acceptance.json', 'presentation-pacing-evidence.schema.json',
 'minecraft-26.3-p0-baseline.json', 'terrain-work-events.schema.json',
 'benchmark-profiles.json', 'metal4-main-production-acceptance.json',
 'iris-performance-acceptance.json'
]
for name in contracts:
 dest = Path('validation/contracts') / name
 dest.parent.mkdir(parents=True, exist_ok=True)
 dest.write_bytes(subprocess.check_output(['git', 'show', SOURCE + ':docs/agent/' + name]))
subprocess.run(['git', 'add', 'validation/contracts'], check=True)
