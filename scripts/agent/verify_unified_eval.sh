#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
export PYTHONPYCACHEPREFIX="$ROOT/build/python-cache"

python3 -m json.tool docs/agent/unified-evaluation-acceptance.json >/dev/null
python3 -m json.tool docs/agent/fixed-cadence-power-trace.schema.json >/dev/null
python3 -m json.tool docs/agent/presentation-pacing-evidence.schema.json >/dev/null
python3 -m json.tool docs/agent/minecraft-26.3-p0-baseline.json >/dev/null
python3 -m json.tool docs/agent/terrain-work-events.schema.json >/dev/null
python3 -m json.tool validation/terrain-work-events/oracle-fixtures.json >/dev/null
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
python3 scripts/agent/verify_terrain_work_events.py --self-test
python3 scripts/agent/verify_frame_evidence.py --self-test
python3 scripts/agent/verify_render_contract_terminal.py --self-test
python3 -m unittest discover -s scripts/agent -p 'test_frame_*.py'
python3 -m py_compile \
  scripts/agent/verify_benchmark_profiles.py \
  scripts/agent/verify_metal4_main_hotpath.py \
  scripts/agent/verify_p1_performance_route.py \
  scripts/agent/check_metal4_main_e2e_pair.py \
  scripts/agent/check_metal4_main_profile_matrix.py \
  scripts/agent/check_metal4_main_trial.py \
  scripts/agent/analyze_unified_eval.py \
  scripts/agent/normalize_unified_trial.py \
  scripts/agent/check_unified_eval_admission.py \
  scripts/agent/verify_terrain_work_events.py \
  scripts/agent/verify_frame_evidence.py
python3 -m py_compile scripts/agent/verify_render_contract_terminal.py
bash -n scripts/agent/doctor.sh
bash -n scripts/agent/run_unified_eval_cycle.sh
bash -n scripts/agent/run_metal4_main_p1_physical_correctness.sh
bash -n scripts/agent/run_metal4_main_p1_physical_performance.sh
bash -n scripts/agent/run_metal4_main_p1_physical_matrix.sh
bash -n scripts/agent/verify.sh

./gradlew --no-daemon compileJava test \
  -x buildMacNative \
  -x buildIOSNative \
  -x buildIOSSpvc \
  --tests com.metallum.client.metal.render.FrameEvidenceRecorderTest \
  --tests com.metallum.client.metal.render.FramePacingPolicyTest \
  --tests com.metallum.client.metal.render.MetalBufferUploadTest \
  --tests com.metallum.client.metal.render.MetalMslDiskCacheTest \
  --tests com.metallum.client.metal.render.MetalHalfTargetOracleTest \
  --tests com.metallum.client.metal.render.MetalRasterDepthPlaneOracleTest \
  --tests com.metallum.client.metal.render.MetalPsoArchiveCacheTest \
  --tests com.metallum.client.metal.render.MetalSurfaceFrameStateTest \
  --tests com.metallum.client.metal.render.MetalSubmissionBackpressureTest \
  --tests com.metallum.client.metal.render.TerrainIcbSubmissionTest \
  --tests com.metallum.client.metal.render.mtl.MTLCommandEncoderLifecycleTest \
  --tests com.metallum.client.metal.render.mtl.MetalRenderStatePacketTest \
  --tests com.metallum.client.metal.render.mtl.MetalRenderStateShadowTest \
  --tests com.metallum.client.metal.render.mtl.MetalComputeStateShadowTest \
  --tests com.metallum.client.terrain.TerrainSchedulingControllerTest \
  --tests com.metallum.client.terrain.BoundedTerrainTaskAdmissionTest \
  --tests com.metallum.client.terrain.TerrainPublicationGenerationGuardTest \
  --tests com.metallum.client.terrain.VanillaTerrainAdmissionTelemetryTest \
  --tests com.metallum.client.terrain.VanillaTerrainAdmissionReportTest \
  --tests com.metallum.client.terrain.TerrainWorkEventRecorderTest \
  --tests com.metallum.client.terrain.TerrainWorkReportTest \
  --tests com.metallum.client.terrain.VanillaTerrainWorkTrackerTest \
  --tests com.metallum.client.terrain.TerrainNativeSignalTest \
  --tests com.metallum.client.terrain.PresentationPacingSnapshotTest \
  --tests com.metallum.client.terrain.PresentationPacingEvidenceAdapterTest \
  --tests com.metallum.mixin.MetallumMixinRegistrationTest \
  --tests com.metallum.client.validation.contract.RenderContractCoreTest \
  --tests com.metallum.client.validation.contract.RenderContractRuntimeTest \
  --tests com.metallum.client.validation.contract.RenderTraceRecorderStreamingTest \
  --tests com.metallum.client.validation.contract.RenderTraceRecorderCompressionTest \
  --tests com.metallum.client.validation.report.RenderContractCompressedEvidenceTest \
  --tests com.metallum.client.validation.storage.ValidationStorageBudgetTest \
  --tests com.metallum.client.metal.render.MetalGpuBufferFrontendContractTest \
  --tests com.metallum.client.metal.render.MetalPrivateBindingHookDescriptorTest.sodiumCloudBulkEmitterIsCoveredAtItsFinalByteBufferBoundary \
  --tests com.metallum.client.metal.render.MetalPrivateBindingHookDescriptorTest.inspectPinnedCullRecoveryBoundaryWithoutPromotingTheOldOptimization \
  --tests com.metallum.client.metal.render.MetalPrivateBindingHookDescriptorTest.inspectPinnedShaderIncludeApi \
  --tests com.metallum.client.metal.render.MetalCloudGeometryTest \
  --tests com.metallum.client.metal.render.MetalCloudMotionHistoryTest \
  --tests com.metallum.client.metal.render.MetalUiBlendContractTest \
  --tests com.metallum.client.metal.render.MetalUiContentProofTest \
  --tests com.metallum.client.metal.render.MetalUiFrameIdentityTest \
  --tests com.metallum.client.metal.render.MetalMotionPixelCoverageTest \
  --tests com.metallum.client.metal.render.MetalMotionFrameManifestTest \
  --tests com.metallum.client.metal.render.MetalRawMotionEvidenceTest \
  --tests com.metallum.client.metal.render.MetalSourceFrameIdentityTest \
  --tests com.metallum.client.metal.render.MetalShaderInputCaptureTest \
  --tests com.metallum.client.metal.render.MetalShaderCompilerFixturesTest \
  --tests com.metallum.client.metal.render.MetalShaderReloadControllerTest \
  --tests com.metallum.client.metal.render.MetalCanonicalShaderReferenceTest \
  --tests com.metallum.client.metal.render.MetalShaderLibraryProofTest \
  --tests com.metallum.client.metal.render.MetalShaderSourceLibraryTest \
  --tests com.metallum.client.metal.render.MetalPreviousVisibilityHistoryTest \
  --tests com.metallum.client.metal.render.MetalParticleVertexAlphaHistoryTest \
  --tests com.metallum.client.metal.render.MetalParticlePartialAlphaHistoryTest \
  --tests com.metallum.client.metal.render.MetalPreviousVertexHistoryTest \
  --tests com.metallum.client.metal.render.MetalSourceUniformsTest \
  --tests com.metallum.client.metal.render.MetalParticleQuadHistoryTest \
  --tests com.metallum.client.metal.render.MetalParticleCrossGroupHistoryTest \
  --tests com.metallum.client.validation.report.RenderContractReportTest

python3 - <<'PY_CULL_RECEIPT'
from pathlib import Path
import base64
import gzip
import json
import os
import xml.etree.ElementTree as ET
path=Path('build/test-results/test/TEST-com.metallum.client.metal.render.MetalPrivateBindingHookDescriptorTest.xml')
suite=ET.parse(path).getroot()
rows=[line for block in suite.iter('system-out') for line in (block.text or '').splitlines()
      if line.startswith('SODIUM_CULL_REVIEW_GZIP_BASE64 ')]
if len(rows)!=1:
    raise SystemExit('missing or ambiguous pinned Sodium cull review receipt')
report=json.loads(gzip.decompress(base64.b64decode(rows[0].split(' ',1)[1])))
expected=os.environ.get('METALLUM_SOURCE_SHA')
if expected and report.get('sourceCommit')!=expected:
    raise SystemExit('pinned cull review receipt belongs to a different source SHA')
if report.get('sameCancellationSemanticsProven') is not False or report.get('promotionAllowed') is not False:
    raise SystemExit('diagnostic hook shape must not silently promote an experimental optimization')
print(rows[0])
api_rows=[line for block in suite.iter('system-out') for line in (block.text or '').splitlines()
          if line.startswith('METAL_SHADER_SOURCE_API_GZIP_BASE64 ')]
if len(api_rows)!=1:
    raise SystemExit('missing or ambiguous pinned shader include API receipt')
api=json.loads(gzip.decompress(base64.b64decode(api_rows[0].split(' ',1)[1])))
if expected and api.get('sourceCommit')!=expected:
    raise SystemExit('shader include API receipt belongs to a different source SHA')
if api.get('sourceAdmissionGranted') is not False:
    raise SystemExit('API signatures must not silently promote unknown shader semantics')
print(api_rows[0])
PY_CULL_RECEIPT

echo "Unified evaluation static verification: PASS"
