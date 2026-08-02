#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

python3 -m json.tool docs/agent/unified-evaluation-acceptance.json >/dev/null
python3 scripts/agent/analyze_unified_eval.py --self-test
bash -n scripts/agent/run_unified_eval_cycle.sh

./gradlew --no-daemon compileJava test \
  -x buildMacNative \
  -x buildIOSNative \
  -x buildIOSSpvc \
  --tests com.metallum.client.terrain.TerrainSchedulingControllerTest \
  --tests com.metallum.client.terrain.TerrainNativeSignalTest \
  --tests com.metallum.mixin.MetallumMixinRegistrationTest \
  --tests com.metallum.client.validation.contract.RenderContractCoreTest \
  --tests com.metallum.client.validation.report.RenderContractReportTest

echo "Unified evaluation static verification: PASS"
