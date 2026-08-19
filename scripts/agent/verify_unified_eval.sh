#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

python3 -m json.tool docs/agent/unified-evaluation-acceptance.json >/dev/null
python3 -m json.tool docs/agent/benchmark-profiles.json >/dev/null
python3 scripts/agent/verify_benchmark_profiles.py
python3 scripts/agent/verify_metal4_main_hotpath.py \
  --output build/agent-evidence/metal4-main-hotpath.json
python3 scripts/agent/check_metal4_main_e2e_pair.py --self-test
python3 scripts/agent/analyze_unified_eval.py --self-test
python3 scripts/agent/normalize_unified_trial.py --self-test
python3 scripts/agent/check_unified_eval_admission.py --self-test
python3 -m py_compile \
  scripts/agent/verify_benchmark_profiles.py \
  scripts/agent/verify_metal4_main_hotpath.py \
  scripts/agent/check_metal4_main_e2e_pair.py \
  scripts/agent/analyze_unified_eval.py \
  scripts/agent/normalize_unified_trial.py \
  scripts/agent/check_unified_eval_admission.py
bash -n scripts/agent/run_unified_eval_cycle.sh
bash -n scripts/agent/verify.sh

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
