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

    def test_jfr_only_reuse_profile_is_diagnostic_and_unpaired(self):
        base = dict(stationary_baseline=True, frame_evidence_phase="stationary",
                    frame_evidence="diagnostic", metrics_only=False,
                    presentation_metrics=False, render_labels=False,
                    terrain_slice_cache=False, reuse_encoder_state=True,
                    reuse_native_encoder_arguments=False)
        resolve = lambda profile, **changes: runner.resolve_optimization_profile(
            profile, **{**base, **changes})
        candidate = resolve(runner.OPTIMIZATION_PROFILE_DIAGNOSTIC_REUSE, jfr_only=True)
        self.assertIsNone(candidate["pairKey"])
        self.assertEqual(candidate["feature"], "encoder-cpu-state-reuse")
        self.assertTrue(candidate["candidate"])
        with self.assertRaisesRegex(ValueError, "requires reuse-encoder-state-diagnostic-v1"):
            resolve(runner.OPTIMIZATION_PROFILE_BASELINE, reuse_encoder_state=False, jfr_only=True)
        with self.assertRaisesRegex(ValueError, "requires reuse-encoder-state-diagnostic-v1"):
            resolve(runner.OPTIMIZATION_PROFILE_REUSE, frame_evidence="timing", jfr_only=True)
        with self.assertRaisesRegex(ValueError, "jfr-only diagnostic"):
            resolve(runner.OPTIMIZATION_PROFILE_DIAGNOSTIC_REUSE, jfr_only=False)
        with self.assertRaisesRegex(ValueError, "jfr-only diagnostic"):
            resolve(runner.OPTIMIZATION_PROFILE_DIAGNOSTIC_REUSE, metrics_only=True, jfr_only=True)
        with self.assertRaisesRegex(ValueError, "jfr-only diagnostic"):
            resolve(runner.OPTIMIZATION_PROFILE_DIAGNOSTIC_REUSE, frame_evidence="timing", jfr_only=True)
        with self.assertRaisesRegex(ValueError, "other diagnostic or optimization"):
            resolve(runner.OPTIMIZATION_PROFILE_DIAGNOSTIC_REUSE,
                    reuse_native_encoder_arguments=True, jfr_only=True)

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

    def test_jfr_only_requires_and_records_nonempty_jfr(self):
        archive = self.archive_fixture(mode="diagnostic")
        def diagnostic_verifier(command, **kwargs):
            return SimpleNamespace(returncode=0, stdout=json.dumps({
                "status": "valid-observation",
                "physicalPerformanceAcceptance": "unverified",
                "instrumentationMode": "diagnostic",
            }), stderr="")

        with self.assertRaisesRegex(RuntimeError, "non-empty gameplay.jfr"):
            runner.finalize_client_run(
                {"gameplay": {"status": "completed"}}, None,
                DeferredClient(self.output, archive), self.output, self.root,
                self.identity, "diagnostic", diagnostic_verifier,
                expected_trial_id="trial-A", expected_phase="stationary",
                expected_warmup_seconds=5, expected_sample_seconds=10,
                jfr_only=True)
        (self.output / "gameplay.jfr").write_bytes(b"jfr")
        receipt = {"gameplay": {"status": "completed"}}
        runner.finalize_client_run(
            receipt, None, DeferredClient(self.output, archive), self.output, self.root,
            self.identity, "diagnostic", diagnostic_verifier,
            expected_trial_id="trial-A", expected_phase="stationary",
            expected_warmup_seconds=5, expected_sample_seconds=10,
            jfr_only=True)
        self.assertEqual(receipt["jfr"], {"path": "gameplay.jfr", "bytes": 3})

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

    def trace_fixture(self, event_seconds=(5.5, 6.0), *, include_units=True):
        toc = self.output / "trace-toc.xml"
        present = self.output / "trace-present-tables.xml"
        schemas = "".join(f'<table schema="{schema}" documentation="Denotes CAMetalDrawable events."/>'
                           for schema in runner.TRACE_PRESENT_SCHEMAS)
        toc.write_text(f"""<?xml version="1.0"?>
<trace-toc><run number="1"><info><target>
<process type="attached" name="java" pid="42"/>
<summary><start-date>2026-09-22T12:00:00+00:00</start-date><duration>30</duration></summary>
</target></info><data>{schemas}</data></run></trace-toc>""")
        rows = []
        for index, seconds in enumerate(event_seconds):
            raw = str(round(seconds * 1_000_000_000)) if include_units else f"{seconds:.3f}"
            fmt = f"00:{seconds:06.3f}"
            process = ('<process id="8"><pid id="9" fmt="42">42</pid></process>'
                       if index == 0 else '<process ref="8"/>')
            rows.append(f'<row><start-time id="{index * 4 + 1}" fmt="{fmt}">{raw}</start-time>'
                        f"{process}</row>")
        schema_one = (f'<schema name="{runner.TRACE_PRESENT_SCHEMAS[0]}" documentation="Denotes CAMetalDrawable presented handlers.">'
                      '<col><mnemonic>timestamp</mnemonic><engineering-type>start-time</engineering-type></col></schema>')
        schema_two = (f'<schema name="{runner.TRACE_PRESENT_SCHEMAS[1]}" documentation="Denotes CAMetalDrawable present requests.">'
                      '<col><mnemonic>timestamp</mnemonic><engineering-type>start-time</engineering-type></col></schema>')
        present.write_text("<trace-query-result>"
                           + f'<node xpath="//trace-toc/run[1]/data/table[1]">{schema_one}{"".join(rows)}</node>'
                           + f'<node xpath="//trace-toc/run[1]/data/table[2]">{schema_two}{"".join(rows)}</node>'
                           + "</trace-query-result>")
        return toc, present

    def trace_gameplay(self):
        return {
            "pid": 42,
            "windowClockAnchors": {"events": [{
                "before": {"monoBeforeNs": 0, "monoAfterNs": 100,
                            "wall": "2026-09-22T12:00:00+00:00"},
                "after": {"monoBeforeNs": 10_000_000_000, "monoAfterNs": 10_000_000_100,
                           "wall": "2026-09-22T12:00:10+00:00"},
            }]},
        }

    def trace_archive(self, *, clock="java-System.nanoTime"):
        return {"window": {"clock": clock, "startNs": 5_000_000_000, "endNs": 6_000_000_000}}

    def test_trace_present_events_are_partial_not_continuous_coverage(self):
        toc, present = self.trace_fixture()
        coverage = runner.inspect_trace_coverage(toc, present, self.trace_gameplay(),
                                                 archive=self.trace_archive())
        self.assertEqual(coverage["status"], "partial")
        self.assertEqual(coverage["reason"], "present-events-do-not-prove-continuous-window-coverage")
        self.assertFalse(coverage["continuousCoverage"])
        self.assertEqual(coverage["pidScope"], "matched-row-pid")

    def test_trace_requires_bracketing_anchors_and_preserves_boundary_uncertainty(self):
        toc, present = self.trace_fixture(event_seconds=(4.999999925,))
        gameplay = self.trace_gameplay()
        coverage = runner.inspect_trace_coverage(toc, present, gameplay, archive=self.trace_archive())
        self.assertEqual(coverage["reason"], "present-events-do-not-prove-continuous-window-coverage")
        del gameplay["windowClockAnchors"]["events"][0]["after"]
        coverage = runner.inspect_trace_coverage(toc, present, gameplay, archive=self.trace_archive())
        self.assertEqual(coverage["reason"], "java-clock-anchors-do-not-bracket-window")

    def test_trace_nonoverlap_and_missing_or_bad_clock_fail_closed(self):
        toc, present = self.trace_fixture(event_seconds=(20.0, 21.0))
        coverage = runner.inspect_trace_coverage(toc, present, self.trace_gameplay(),
                                                 archive=self.trace_archive())
        self.assertEqual(coverage["status"], "partial")
        self.assertEqual(coverage["reason"], "present-events-do-not-overlap-archive-window")
        missing = runner.inspect_trace_coverage(toc, self.output / "missing.xml", self.trace_gameplay(),
                                                archive=self.trace_archive())
        self.assertEqual(missing["status"], "unavailable")
        self.assertEqual(missing["reason"], "present-table-export-missing")
        bad_clock = runner.inspect_trace_coverage(toc, present, self.trace_gameplay(),
                                                   archive=self.trace_archive(clock="native-presented-time"))
        self.assertEqual(bad_clock["status"], "unavailable")
        self.assertEqual(bad_clock["reason"], "archive-window-clock-is-not-java-system-nanotime")
        jumped = self.trace_gameplay()
        jumped["windowClockAnchors"]["events"][0]["after"]["wall"] = "2026-09-22T12:00:20+00:00"
        jump = runner.inspect_trace_coverage(toc, present, jumped, archive=self.trace_archive())
        self.assertEqual(jump["status"], "unavailable")
        self.assertEqual(jump["reason"], "java-clock-anchor-offsets-disagree")

    def test_trace_event_time_without_units_is_unavailable(self):
        toc, present = self.trace_fixture(include_units=False)
        coverage = runner.inspect_trace_coverage(toc, present, self.trace_gameplay(),
                                                 archive=self.trace_archive())
        self.assertEqual(coverage["status"], "unavailable")
        self.assertEqual(coverage["reason"], "present-table-event-time-unit-missing-or-invalid")

    def test_trace_exports_attach_conservative_coverage_to_receipt(self):
        toc, present = self.trace_fixture()
        (self.output / "gameplay.trace").write_bytes(b"trace")
        (self.output / "frame-evidence.json").write_text(json.dumps(self.trace_archive()))
        calls = []

        def export(command, **kwargs):
            calls.append(command)
            self.assertTrue(kwargs["check"])
            return SimpleNamespace(returncode=0)

        receipt = {"gameplay": self.trace_gameplay()}
        coverage = runner.export_trace_artifacts(self.output, receipt, export)
        self.assertEqual(coverage["status"], "partial")
        self.assertEqual(receipt["traceCoverage"]["reason"],
                         "present-events-do-not-prove-continuous-window-coverage")
        self.assertEqual(len(calls), 2)
        self.assertIn("--toc", calls[0])
        self.assertIn("--xpath", calls[1])


if __name__ == "__main__":
    unittest.main()
