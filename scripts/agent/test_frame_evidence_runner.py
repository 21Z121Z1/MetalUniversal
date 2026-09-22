import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/agent/record_vanilla_gameplay.py"
SPEC = importlib.util.spec_from_file_location("record_vanilla_gameplay", SCRIPT)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class DeferredClient:
    def __init__(self, output, archive=None):
        self.output = output
        self.archive = archive

    def wait(self, timeout):
        if self.archive is not None:
            (self.output / "frame-evidence.json").write_text(json.dumps(self.archive))
        return 0


class FrameEvidenceRunnerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name)
        self.root = self.output / "root"
        self.root.mkdir()
        self.identity = "a" * 40

    def fake_verifier(self, command, **kwargs):
        self.verifier_calls += 1
        return SimpleNamespace(returncode=0, stdout=json.dumps({
            "status": "valid-observation",
            "physicalPerformanceAcceptance": "unverified",
            "instrumentationMode": "timing",
        }), stderr="")

    def rejecting_verifier(self, command, **kwargs):
        self.verifier_calls += 1
        return SimpleNamespace(returncode=1, stdout=json.dumps({
            "status": "invalid-evidence",
            "reason": "hash chain mismatch",
        }), stderr="")

    def archive_fixture(self, mode="timing", trial_id="trial-A", phase="stationary", complete=True):
        return {
            "schemaVersion": 2,
            "identity": {"instrumentationMode": mode, "trialId": trial_id},
            "window": {"profile": {"route": {
                "samplePhase": "stationary-full-view" if phase == "stationary" else "flight-new-chunks",
                "warmupSeconds": 5,
                "sampleSeconds": 10,
                "warmupNs": 5_000_000_000,
                "sampleNs": 10_000_000_000,
            }}},
            "archive": {"complete": complete},
        }

    def run_completed(self, client, mode="timing", verifier=None, trial_id="trial-A", phase="stationary",
                      expected_optimization_profile=None):
        self.verifier_calls = 0
        receipt = {"gameplay": {"status": "completed"}}
        runner.finalize_client_run(receipt, None, client, self.output, self.root,
                                   self.identity, mode, verifier or self.fake_verifier,
                                   expected_trial_id=trial_id, expected_phase=phase,
                                   expected_warmup_seconds=5, expected_sample_seconds=10,
                                   expected_optimization_profile=expected_optimization_profile)
        return receipt

    def test_archive_written_during_client_wait_is_verified_after_wait(self):
        receipt = self.run_completed(DeferredClient(self.output, self.archive_fixture()))
        self.assertEqual(self.verifier_calls, 1)
        self.assertEqual(receipt["frameEvidenceVerification"]["status"], "valid-observation")
        self.assertEqual(receipt["frameEvidenceVerification"]["trialId"], "trial-A")
        self.assertTrue((self.output / "frame-evidence-verification.json").is_file())

    def test_missing_or_invalid_final_archive_fails(self):
        with self.assertRaisesRegex(RuntimeError, "did not export"):
            self.run_completed(DeferredClient(self.output))
        with self.assertRaisesRegex(RuntimeError, "bounded archive schema"):
            self.run_completed(DeferredClient(self.output, {"schemaVersion": 1}))
        with self.assertRaisesRegex(RuntimeError, "not complete"):
            self.run_completed(DeferredClient(self.output, self.archive_fixture(complete=False)))

    def test_archive_identity_matches_requested_mode_trial_and_phase(self):
        cases = (
            (self.archive_fixture(mode="diagnostic"), "mode"),
            (self.archive_fixture(trial_id="trial-B"), "trial"),
            (self.archive_fixture(phase="streaming"), "phase"),
            ({**self.archive_fixture(), "window": {"profile": {"route": {
                "samplePhase": "stationary-full-view", "warmupNs": 6_000_000_000, "sampleNs": 10_000_000_000,
            }}}}, "window"),
        )
        for archive, label in cases:
            with self.subTest(label=label):
                with self.assertRaisesRegex(RuntimeError, "differs"):
                    self.run_completed(DeferredClient(self.output, archive))

    def test_verifier_rejection_fails_and_preserves_invalid_report(self):
        with self.assertRaisesRegex(RuntimeError, "verification failed"):
            self.run_completed(DeferredClient(self.output, self.archive_fixture()), verifier=self.rejecting_verifier)
        self.assertEqual(self.verifier_calls, 1)
        self.assertEqual(json.loads((self.output / "frame-evidence-verification.json").read_text())["status"],
                         "invalid-evidence")

    def test_off_mode_does_not_require_archive_or_invoke_verifier(self):
        receipt = self.run_completed(DeferredClient(self.output), mode="off")
        self.assertEqual(self.verifier_calls, 0)
        self.assertIsNone(receipt["frameEvidenceVerification"])

    def test_initial_world_identity_is_verified_and_recorded(self):
        snapshot = self.output / "initial-world"
        snapshot.mkdir()
        (snapshot / "level.dat").write_bytes(b"snapshot")
        digest = "d" * 64
        manifest = snapshot.with_name("initial-world-manifest.json")
        manifest.write_text(json.dumps({
            "identity": {"snapshotDirectory": "initial-world", "snapshotSha256": digest},
            "files": [{"path": "level.dat", "sha256": "f" * 64, "bytes": 8}],
        }))
        supplied = runner.snapshot_identity(snapshot)
        receipt = {"gameplay": {"world": {"replaySourceSnapshotSha256": digest}}}
        runner.record_snapshot_identity(receipt, supplied)
        self.assertEqual(receipt["initialWorld"]["runtimeSnapshotSha256"], digest)
        with self.assertRaisesRegex(RuntimeError, "differs"):
            runner.record_snapshot_identity(
                {"gameplay": {"world": {"replaySourceSnapshotSha256": "e" * 64}}}, supplied)

    def test_window_contract_bounds_stationary_and_rejects_long_streaming_route(self):
        self.assertEqual(runner.validate_window(5, 10, "stationary"), 15)
        self.assertEqual(runner.validate_window(60, 120, "stationary"), 180)
        self.assertEqual(runner.gameplay_timeout_seconds(15), 300)
        self.assertEqual(runner.gameplay_timeout_seconds(240), 420)
        with self.assertRaisesRegex(ValueError, "streaming"):
            runner.validate_window(5, 11, "streaming")
        with self.assertRaisesRegex(ValueError, "must not exceed"):
            runner.validate_window(181, 120, "stationary")
        with self.assertRaisesRegex(ValueError, "phase"):
            runner.validate_window(5, 10, "unknown")

    def test_reuse_candidate_requires_explicit_stationary_pair(self):
        baseline = runner.resolve_optimization_profile(
            runner.OPTIMIZATION_PROFILE_BASELINE,
            stationary_baseline=True, frame_evidence_phase="stationary",
            frame_evidence="timing", metrics_only=True,
            presentation_metrics=False, render_labels=False,
            terrain_slice_cache=False, reuse_encoder_state=False)
        candidate = runner.resolve_optimization_profile(
            runner.OPTIMIZATION_PROFILE_REUSE,
            stationary_baseline=True, frame_evidence_phase="stationary",
            frame_evidence="timing", metrics_only=True,
            presentation_metrics=False, render_labels=False,
            terrain_slice_cache=False, reuse_encoder_state=True)
        self.assertEqual(baseline["pairKey"], candidate["pairKey"])
        self.assertFalse(baseline["reuseEncoderState"])
        self.assertFalse(baseline["reuseNativeEncoderArguments"])
        self.assertTrue(candidate["reuseEncoderState"])
        with self.assertRaisesRegex(ValueError, "optimization-profile"):
            runner.resolve_optimization_profile(
                runner.OPTIMIZATION_PROFILE_BASELINE,
                stationary_baseline=True, frame_evidence_phase="stationary",
                frame_evidence="timing", metrics_only=True,
                presentation_metrics=False, render_labels=False,
                terrain_slice_cache=False, reuse_encoder_state=True)
        with self.assertRaisesRegex(ValueError, "stationary"):
            runner.resolve_optimization_profile(
                runner.OPTIMIZATION_PROFILE_REUSE,
                stationary_baseline=False, frame_evidence_phase="streaming",
                frame_evidence="timing", metrics_only=True,
                presentation_metrics=False, render_labels=False,
                terrain_slice_cache=False, reuse_encoder_state=True)

    def test_encoder_argument_candidate_is_independent_and_requires_no_other_reuse(self):
        candidate = runner.resolve_optimization_profile(
            runner.OPTIMIZATION_PROFILE_ARGUMENT_REUSE,
            stationary_baseline=True, frame_evidence_phase="stationary",
            frame_evidence="timing", metrics_only=True,
            presentation_metrics=False, render_labels=False,
            terrain_slice_cache=False, reuse_encoder_state=False,
            reuse_native_encoder_arguments=True)
        self.assertEqual(candidate["feature"], "encoder-native-argument-reuse")
        self.assertFalse(candidate["reuseEncoderState"])
        self.assertTrue(candidate["reuseNativeEncoderArguments"])
        self.assertTrue(candidate["candidate"])
        with self.assertRaisesRegex(ValueError, "requires --reuse-native-encoder-arguments"):
            runner.resolve_optimization_profile(
                runner.OPTIMIZATION_PROFILE_ARGUMENT_REUSE,
                stationary_baseline=True, frame_evidence_phase="stationary",
                frame_evidence="timing", metrics_only=True,
                presentation_metrics=False, render_labels=False,
                terrain_slice_cache=False, reuse_encoder_state=False)
        with self.assertRaisesRegex(ValueError, "cannot enable encoder-state reuse"):
            runner.resolve_optimization_profile(
                runner.OPTIMIZATION_PROFILE_ARGUMENT_REUSE,
                stationary_baseline=True, frame_evidence_phase="stationary",
                frame_evidence="timing", metrics_only=True,
                presentation_metrics=False, render_labels=False,
                terrain_slice_cache=False, reuse_encoder_state=True,
                reuse_native_encoder_arguments=True)

    def test_candidate_requires_runtime_activation_evidence(self):
        expected = runner.resolve_optimization_profile(
            runner.OPTIMIZATION_PROFILE_REUSE,
            stationary_baseline=True, frame_evidence_phase="stationary",
            frame_evidence="timing", metrics_only=True,
            presentation_metrics=False, render_labels=False,
            terrain_slice_cache=False, reuse_encoder_state=True)
        receipt = {"gameplay": {"optimizationProfile": expected,
                                "optimizationActivation": {"active": True,
                                                            "packetStorageReuseHits": 2,
                                                            "shadowReuseHits": 2}}}
        runner.verify_gameplay_optimization(receipt, expected)
        self.assertEqual(receipt["optimizationActivation"]["packetStorageReuseHits"], 2)
        receipt["gameplay"]["optimizationActivation"]["active"] = False
        with self.assertRaisesRegex(RuntimeError, "did not report active"):
            runner.verify_gameplay_optimization(receipt, expected)

    def test_completed_archive_must_repeat_gameplay_optimization_profile(self):
        expected = runner.resolve_optimization_profile(
            runner.OPTIMIZATION_PROFILE_REUSE,
            stationary_baseline=True, frame_evidence_phase="stationary",
            frame_evidence="timing", metrics_only=True,
            presentation_metrics=False, render_labels=False,
            terrain_slice_cache=False, reuse_encoder_state=True)
        archive = self.archive_fixture()
        archive["window"]["profile"]["optimizationProfile"] = expected
        receipt = {"gameplay": {"status": "completed", "optimizationProfile": expected,
                                "optimizationActivation": {"active": True}}}
        self.verifier_calls = 0
        runner.finalize_client_run(
            receipt, None, DeferredClient(self.output, archive), self.output, self.root,
            self.identity, "timing", self.fake_verifier,
            expected_trial_id="trial-A", expected_phase="stationary",
            expected_warmup_seconds=5, expected_sample_seconds=10,
            expected_optimization_profile=expected)
        self.assertEqual(receipt["frameEvidenceVerification"]["optimizationProfile"]["id"],
                         runner.OPTIMIZATION_PROFILE_REUSE)

        archive["window"]["profile"]["optimizationProfile"] = {
            **expected, "id": runner.OPTIMIZATION_PROFILE_BASELINE,
            "reuseEncoderState": False, "candidate": False,
        }
        with self.assertRaisesRegex(RuntimeError, "archive optimization profile differs"):
            runner.finalize_client_run(
                {"gameplay": {"status": "completed"}}, None,
                DeferredClient(self.output, archive), self.output, self.root,
                self.identity, "timing", self.fake_verifier,
                expected_trial_id="trial-A", expected_phase="stationary",
                expected_warmup_seconds=5, expected_sample_seconds=10,
                expected_optimization_profile=expected)


if __name__ == "__main__":
    unittest.main()
