#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

python3 -m json.tool docs/agent/unified-evaluation-acceptance.json >/dev/null
python3 -m json.tool docs/agent/presentation-pacing-evidence.schema.json >/dev/null
python3 -m json.tool docs/agent/benchmark-profiles.json >/dev/null
python3 -m json.tool docs/agent/metal4-main-production-acceptance.json >/dev/null
python3 scripts/agent/verify_benchmark_profiles.py
python3 scripts/agent/verify_metal4_main_hotpath.py \
  --output build/agent-evidence/metal4-main-hotpath.json
python3 scripts/agent/verify_p1_performance_route.py
python3 scripts/agent/check_metal4_main_e2e_pair.py --self-test
python3 scripts/agent/check_metal4_main_profile_matrix.py --self-test
python3 scripts/agent/check_metal4_main_trial.py --self-test
python3 scripts/agent/analyze_unified_eval.py --self-test
python3 scripts/agent/normalize_unified_trial.py --self-test
python3 scripts/agent/check_unified_eval_admission.py --self-test
python3 -m py_compile \
  scripts/agent/verify_benchmark_profiles.py \
  scripts/agent/verify_metal4_main_hotpath.py \
  scripts/agent/verify_p1_performance_route.py \
  scripts/agent/check_metal4_main_e2e_pair.py \
  scripts/agent/check_metal4_main_profile_matrix.py \
  scripts/agent/check_metal4_main_trial.py \
  scripts/agent/analyze_unified_eval.py \
  scripts/agent/normalize_unified_trial.py \
  scripts/agent/check_unified_eval_admission.py
bash -n scripts/agent/run_unified_eval_cycle.sh
bash -n scripts/agent/run_unified_eval_cycle_impl.sh
bash -n scripts/agent/run_metal4_main_p1_physical_correctness.sh
bash -n scripts/agent/run_metal4_main_p1_physical_performance.sh
bash -n scripts/agent/run_metal4_main_p1_physical_matrix.sh
bash -n scripts/agent/verify.sh

# Parse/configure the task-scoped Gradle init script in the same Gradle version
# used by the repository. It is a no-op for `help`; its fixed-drawable override
# activates only when minecraftNativeRenderEfficiencyValidation is in the task graph.
./gradlew --no-daemon -I scripts/agent/fixed_drawable.init.gradle help >/dev/null

./gradlew --no-daemon compileJava test \
  -x buildMacNative \
  -x buildIOSNative \
  -x buildIOSSpvc \
  --tests com.metallum.client.terrain.TerrainSchedulingControllerTest \
  --tests com.metallum.client.terrain.TerrainNativeSignalTest \
  --tests com.metallum.client.terrain.PresentationPacingSnapshotTest \
  --tests com.metallum.client.terrain.PresentationPacingEvidenceAdapterTest \
  --tests com.metallum.mixin.MetallumMixinRegistrationTest \
  --tests com.metallum.client.validation.contract.RenderContractCoreTest \
  --tests com.metallum.client.validation.report.RenderContractReportTest

echo "Unified evaluation static verification: PASS"
