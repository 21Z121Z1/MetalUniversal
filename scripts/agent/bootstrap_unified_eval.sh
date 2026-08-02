#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

TARGET_BRANCH="agent/unified-render-eval-performance"
SOURCE_BRANCH="codex/eval-framework-v0"

git config user.name "MetalUniversal Eval Integrator"
git config user.email "actions@users.noreply.github.com"
git fetch origin "$SOURCE_BRANCH"

if ! git merge-base --is-ancestor "origin/$SOURCE_BRANCH" HEAD; then
  set +e
  git merge --no-ff --no-commit "origin/$SOURCE_BRANCH"
  merge_status=$?
  set -e
  if (( merge_status != 0 )); then
    while IFS= read -r conflicted; do
      [[ -n "$conflicted" ]] || continue
      git checkout --ours -- "$conflicted"
      git add "$conflicted"
    done < <(git diff --name-only --diff-filter=U)
  fi
  git add -A
  git commit -m "merge(eval): preserve render-eval framework history"
fi

# MetalGpuTimingRecorder, MetalNativeBridge and MetallumNative.swift merge
# cleanly from the historical branch. Only the mixin registry conflicts with
# the newer Iris performance branch and needs a semantic reconciliation.
python3 <<'PY'
import json
from pathlib import Path

path = Path("src/main/resources/metallum.mixins.json")
config = json.loads(path.read_text(encoding="utf-8"))
client = config["client"]
additions = [
    "sodium.MinecraftTerrainSchedulingMixin",
    "sodium.RenderRegionManagerTerrainSchedulingMixin",
    "sodium.RenderSectionManagerTerrainSchedulingMixin",
    "sodium.SodiumWorldRendererTerrainSchedulingMixin",
]
insertion = client.index("sodium.DrawBackendMixin") if "sodium.DrawBackendMixin" in client else len(client)
for name in reversed(additions):
    if name not in client:
        client.insert(insertion, name)
path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
PY

python3 -m json.tool src/main/resources/metallum.mixins.json >/dev/null
./gradlew --no-daemon compileJava test \
  --tests com.metallum.client.terrain.TerrainSchedulingControllerTest \
  --tests com.metallum.client.terrain.TerrainNativeSignalTest \
  --tests com.metallum.mixin.MetallumMixinRegistrationTest

rm -f .github/workflows/unified-eval-bootstrap.yml
rm -f .github/workflows/unified-eval-pr-bootstrap.yml
rm -f scripts/agent/bootstrap_unified_eval.sh

git add -A
git commit -m "refactor(eval): reconcile correctness and performance foundations [unified-eval-generated]"
git push origin "HEAD:$TARGET_BRANCH"
