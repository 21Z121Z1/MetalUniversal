#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

python3 -m json.tool docs/agent/system-registry.json >/dev/null
python3 -m json.tool docs/agent/unified-evaluation-acceptance.json >/dev/null
python3 -m json.tool docs/agent/presentation-pacing-evidence.schema.json >/dev/null
python3 -m json.tool docs/agent/minecraft-26.3-p0-baseline.json >/dev/null
python3 -m json.tool docs/agent/terrain-work-events.schema.json >/dev/null
python3 -m json.tool validation/terrain-work-events/oracle-fixtures.json >/dev/null
python3 -m json.tool docs/agent/benchmark-profiles.json >/dev/null
python3 -m json.tool docs/agent/metal4-main-production-acceptance.json >/dev/null
python3 scripts/agent/context.py --self-test
python3 scripts/agent/checkpoint.py --self-test
python3 scripts/agent/verify_agent_control_plane.py
python3 scripts/agent/verify_benchmark_profiles.py
python3 scripts/agent/verify_metal4_main_hotpath.py \
  --output build/agent-evidence/metal4-main-hotpath.json
python3 scripts/agent/verify_p1_performance_route.py
python3 scripts/agent/verify_native_encoder_coverage.py
python3 scripts/agent/verify_native_attachment_coverage.py --self-test
python3 scripts/agent/verify_native_attachment_coverage.py
python3 scripts/agent/verify_native_attachment_facts.py --self-test
python3 scripts/agent/test_estimate_attachment_actions.py
python3 scripts/agent/verify_world_stages.py --self-test
python3 scripts/agent/verify_numeric_bindings.py --self-test
python3 scripts/agent/verify_resource_allocations.py --self-test
python3 scripts/agent/verify_resource_allocation_coverage.py --self-test
python3 scripts/agent/verify_resource_allocation_coverage.py
python3 scripts/agent/check_metal4_main_e2e_pair.py --self-test
python3 scripts/agent/check_metal4_main_profile_matrix.py --self-test
python3 scripts/agent/check_metal4_main_trial.py --self-test
python3 scripts/agent/analyze_unified_eval.py --self-test
python3 scripts/agent/normalize_unified_trial.py --self-test
python3 scripts/agent/test_normalize_unified_trial.py
python3 scripts/agent/check_unified_eval_admission.py --self-test
python3 scripts/agent/verify_terrain_work_events.py --self-test
python3 scripts/agent/verify_terrain_generation.py --self-test
python3 scripts/agent/test_verify_terrain_generation.py
python3 -m py_compile \
  scripts/agent/context.py \
  scripts/agent/checkpoint.py \
  scripts/agent/verify_agent_control_plane.py \
  scripts/agent/verify_benchmark_profiles.py \
  scripts/agent/verify_metal4_main_hotpath.py \
  scripts/agent/verify_p1_performance_route.py \
  scripts/agent/check_metal4_main_e2e_pair.py \
  scripts/agent/check_metal4_main_profile_matrix.py \
  scripts/agent/check_metal4_main_trial.py \
  scripts/agent/analyze_unified_eval.py \
  scripts/agent/normalize_unified_trial.py \
  scripts/agent/estimate_attachment_actions.py \
  scripts/agent/check_unified_eval_admission.py \
  scripts/agent/verify_terrain_work_events.py
bash -n scripts/agent/doctor.sh
bash -n scripts/agent/run_unified_eval_cycle.sh
bash -n scripts/agent/run_metal4_main_p1_physical_correctness.sh
bash -n scripts/agent/run_metal4_main_p1_physical_performance.sh
bash -n scripts/agent/run_metal4_main_p1_physical_matrix.sh
bash -n scripts/agent/verify.sh

./gradlew --no-daemon compileJava gpuTimingRecorderRetentionTest numericBindingDiagnosticsEnabledTest test \
  -x buildMacNative \
  -x buildIOSNative \
  -x buildIOSSpvc \
  --tests com.metallum.client.terrain.TerrainSchedulingControllerTest \
  --tests com.metallum.client.terrain.BoundedTerrainTaskAdmissionTest \
  --tests com.metallum.client.terrain.TerrainPublicationGenerationGuardTest \
  --tests com.metallum.client.terrain.VanillaTerrainGenerationTelemetryTest \
  --tests com.metallum.client.terrain.VanillaTerrainAdmissionTelemetryTest \
  --tests com.metallum.client.terrain.VanillaTerrainAdmissionReportTest \
  --tests com.metallum.client.terrain.TerrainWorkEventRecorderTest \
  --tests com.metallum.client.terrain.TerrainWorkReportTest \
  --tests com.metallum.client.terrain.TerrainUploadPressureCountersTest \
  --tests com.metallum.client.terrain.VanillaTerrainUploadPressureTest \
  --tests com.metallum.client.terrain.VanillaTerrainWorkTrackerTest \
  --tests com.metallum.client.terrain.TerrainNativeSignalTest \
  --tests com.metallum.client.terrain.PresentationPacingSnapshotTest \
  --tests com.metallum.client.terrain.PresentationPacingEvidenceAdapterTest \
  --tests com.metallum.mixin.MetallumMixinRegistrationTest \
  --tests com.metallum.client.validation.telemetry.WorldStageRecorderTest \
  --tests com.metallum.mixin.world.WorldStageWrapperTest \
  --tests com.metallum.client.validation.FrameMeasurementWindowTest \
  --tests com.metallum.client.validation.GpuMeasurementWindowTest \
  --tests com.metallum.client.validation.EncoderMeasurementWindowTest \
  --tests com.metallum.client.validation.ProcessMemoryMeasurementTest \
  --tests com.metallum.client.metal.render.NativeAttachmentActionsTest \
  --tests com.metallum.client.metal.render.NativeResourceAllocationsTest \
  --tests com.metallum.client.metal.render.NumericBindingDiagnosticsTest \
  --tests com.metallum.client.validation.contract.RenderContractCoreTest \
  --tests com.metallum.client.validation.report.RenderContractReportTest

python3 scripts/agent/verify_terrain_generation.py \
  build/agent-state/terrain-generation-java-fixture.json --require-active \
  --output build/agent-state/terrain-generation-java-fixture.oracle.json

PYTHONPATH="$ROOT/scripts/agent" python3 - "$ROOT/build/agent-state/process-memory-java-fixture.json" <<'PY'
import json, sys
from normalize_unified_trial import validate_process_memory
with open(sys.argv[1], encoding="utf-8") as source:
    fixture = json.load(source)
raw, errors = validate_process_memory(fixture, fixture["measurementWindow"])
if raw is None or errors:
    raise SystemExit("process-memory fixture invalid: " + repr(errors))
print("Process-memory Java fixture: PASS")
PY

python3 scripts/agent/verify_world_stages.py build/agent-state/world-stage-java-fixture.json --output build/agent-state/world-stage-java-fixture.oracle.json

echo "Unified evaluation static verification: PASS"
