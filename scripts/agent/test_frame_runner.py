"""Synthetic protocol fixtures only; no OS/window/presentation result is simulated as physical."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from frame_trial_contract import (artifact_inventory, atomic_json, canonical_hash, parse_json, runner_lock,
                                  validate_gameplay, verify_inventory, trial_order)
from record_vanilla_gameplay import run_workload, workload_command
from run_frame_trials import build_plan, trial_command, verify_block


class FrameRunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def fixture(self, workload="P0", producer="vanilla"):
        spec = {"workloadId": workload, "producer": producer, "backend": "metal3", "sampleNs": 1000,
                "warmupNs": 100, "targetFps": 60, "output": {"width": 1920, "height": 1080},
                "initialWorld": None, "shaderPack": {"stagedName": "fixture.zip"} if producer == "iris" else None}
        source = {"startNs": 200, "endNs": 1200, "count": 3, "intervalCount": 2, "fps": 3e6, "complete": True,
                  "clock": "System.nanoTime", "membership": "half-open [startNs,endNs)", "invalidTimestamps": 0}
        settings = {"renderDistance": 32, "effectiveRenderDistance": 32,
                    **{key: 1920 for key in ("nativeWindowPixelWidth", "presentWidth", "framebufferWidth", "renderWidth")},
                    **{key: 1080 for key in ("nativeWindowPixelHeight", "presentHeight", "framebufferHeight", "renderHeight")}}
        producer_receipt = {"producer": producer, "sodiumInstalled": producer != "vanilla", "irisInstalled": producer == "iris"}
        if producer == "iris": producer_receipt.update(generation=7, shaderPack="fixture.zip")
        actions = [f"flight-leg-{n}" for n in range(6)] if workload == "T0" else ["fixed-view"]
        report = {"status": "completed", "backendRequested": "metal3", "metal4MainRendererActive": False,
                  "settings": settings, "finalSettings": copy.deepcopy(settings), "sourceSampleWindow": source,
                  "sourceFrames": {**source, "invalidSettingsFrames": 0, "throttledFrames": 0, "droppedSamples": 0},
                  "frameEvidenceProfile": {"workloadId": workload, "protocolVersion": 1,
                      "workloadSha256": canonical_hash(spec), "route": {"warmupNs": 100, "sampleNs": 1000},
                      "quality": copy.deepcopy(settings), "targetIntent": {"fpsLimit": 60, "vsync": True,
                          "authority": "requested-options-not-system-deadline"}, "initialContent": {}},
                  "workload": {"workloadId": workload, "protocolVersion": 1, "completed": True,
                      "sourceSampleStartNs": 200, "sourceSampleEndNs": 1200, "completionNs": 1250,
                      "producerBefore": producer_receipt, "producerAfter": copy.deepcopy(producer_receipt),
                      "serverTickAtActionsStart": 100, "serverTickAtCompletion": 120,
                      "horizontalDisplacementBlocks": 200,
                      "actions": [{"id": name, "sourceClockNs": 210 + n * 20} for n, name in enumerate(actions)]}}
        return spec, report

    def test_source_receipt_is_explicitly_not_presentation_or_product_acceptance(self):
        for workload, producer in (("P0", "vanilla"), ("T0", "sodium"), ("I0", "iris")):
            spec, report = self.fixture(workload, producer)
            result = validate_gameplay(report, spec)
            self.assertEqual("valid-observation", result["status"])
            self.assertFalse(result["productPromotable"])
            self.assertEqual("physical-validation-required", result["physicalPerformanceAcceptance"])
            self.assertEqual(20, result["serverTicks"])

    def test_incomplete_routes_changed_quality_and_hidden_warmup_actions_are_rejected(self):
        changes = [lambda r: r.update(status="failed"), lambda r: r["sourceSampleWindow"].update(complete=False),
                   lambda r: r["sourceSampleWindow"].update(endNs=1201),
                   lambda r: r["workload"]["actions"][0].update(sourceClockNs=199),
                   lambda r: r["workload"]["actions"].pop(),
                   lambda r: r["workload"].update(completionNs=1199),
                   lambda r: r["finalSettings"].update(renderWidth=960),
                   lambda r: r["sourceFrames"].update(droppedSamples=1),
                   lambda r: r["workload"].update(serverTickAtCompletion=100),
                   lambda r: r["frameEvidenceProfile"].update(workloadSha256="0" * 64),
                   lambda r: r["sourceSampleWindow"].update(fps=60)]
        for change in changes:
            spec, report = self.fixture("T0"); change(report)
            with self.assertRaises(ValueError): validate_gameplay(report, spec)

    def test_iris_installed_is_not_pack_activation_or_stable_generation(self):
        for change in (lambda r: r["workload"]["producerBefore"].update(generation=-1),
                       lambda r: r["workload"]["producerBefore"].update(shaderPack="wrong.zip"),
                       lambda r: r["workload"]["producerAfter"].update(generation=8),
                       lambda r: r["workload"]["producerAfter"].update(irisInstalled=False)):
            spec, report = self.fixture("I1", "iris"); change(report)
            with self.assertRaises(ValueError): validate_gameplay(report, spec)

    def test_hash_inventory_detects_missing_altered_and_new_raw_artifacts(self):
        (self.root / "client.log").write_text("original")
        (self.root / "trial-manifest.json").write_text("not-self-hashed")
        records = artifact_inventory(self.root); verify_inventory(self.root, records)
        (self.root / "extra.json").write_text("unexpected")
        with self.assertRaises(ValueError): verify_inventory(self.root, records)
        (self.root / "extra.json").unlink(); (self.root / "client.log").write_text("changed")
        with self.assertRaises(ValueError): verify_inventory(self.root, records)
        (self.root / "client.log").unlink()
        with self.assertRaises(ValueError): verify_inventory(self.root, records)

    def test_unfinished_block_cannot_become_comparison_ready(self):
        plan = {"schemaVersion": 1, "order": [{"ordinal": 1, "block": 1, "slot": 1, "variant": "A"}], "protocol": "aa"}
        atomic_json(self.root / "plan.json", plan)
        atomic_json(self.root / "block.json", {"schemaVersion": 1, "complete": False, "planSha256": canonical_hash(plan), "trials": []})
        with self.assertRaises(ValueError): verify_block(self.root)

    def test_failed_trial_is_retained_and_cannot_be_averaged_away(self):
        variant = {"observer": "timing", "backend": "metal3", "producer": "vanilla", "artifact": {}, "flags": {}}
        order = trial_order("aa", 1)
        plan = {"schemaVersion": 1, "order": order, "protocol": "aa", "variants": {"A": variant, "B": variant}}
        atomic_json(self.root / "plan.json", plan)
        trials = []
        for row in order:
            name = f"trial-{row['ordinal']:04d}-{row['variant']}"
            directory = self.root / name; directory.mkdir()
            manifest = {"trialId": name, "status": "unavailable", "complete": False, "artifacts": []}
            atomic_json(directory / "trial-manifest.json", manifest)
            trials.append({"order": row, "directory": name, "exitCode": 2, "manifestSha256": canonical_hash(manifest)})
        atomic_json(self.root / "block.json", {"schemaVersion": 1, "planSha256": canonical_hash(plan), "complete": True, "trials": trials})
        result = verify_block(self.root)
        self.assertEqual("invalid-evidence", result["status"])
        self.assertEqual(2, result["failedTrialCount"])
        self.assertEqual(2, result["variants"]["A"]["count"])
        self.assertIsNone(result["variants"]["A"]["sourceFpsMeanAcrossTrials"])
        self.assertEqual(2, len(result["variants"]["A"]["trials"]))

    def jar(self):
        identity = {"sourceSha": "a" * 40, "treeSha": "b" * 40, "dirty": False, "minecraftVersion": "26.3"}
        jar = self.root / "input with spaces.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("metallum-build-identity.json", json.dumps(identity))
            archive.writestr("natives/macos/libmetallum-build-identity.json", json.dumps({"schemaVersion": 1, "build": identity,
                "nativeSha256": hashlib.sha256(b"native").hexdigest()}))
            archive.writestr("natives/macos/libmetallum.dylib", b"native")
        return jar

    def arguments(self):
        return argparse.Namespace(workload="P0", producer="vanilla", backend="metal3", warmup_seconds=30,
            sample_seconds=120, target_fps=60, metrics_only=True, render_labels=False, presentation_metrics=False,
            stationary_baseline=False, prepare_scene=False, bootstrap=True, initial_world=None, shader_pack=None,
            shader_pack_sha256=None, jar=self.jar(), output=self.root / "trial", frame_evidence="timing",
            reuse_encoder_state=False, terrain_slice_cache=False, verify_terrain_cache=False, preflight_only=False)

    def test_missing_hardware_emits_unavailable_without_fabricating_an_exit_code_or_result(self):
        args = self.arguments()
        with patch.dict(os.environ, {}, clear=True), patch("record_vanilla_gameplay.subprocess.check_output", side_effect=["a" * 40, "b" * 40, ""]), \
                patch("record_vanilla_gameplay.physical_display", side_effect=RuntimeError("physical-validation-required: no real display")):
            self.assertEqual(2, run_workload(args, self.root))
        manifest = parse_json((args.output / "trial-manifest.json").read_bytes())
        self.assertFalse(manifest["complete"])
        self.assertEqual("unavailable", manifest["status"])
        self.assertIsNone(manifest["exitCode"])
        self.assertNotIn("observation", manifest)
        self.assertFalse(manifest["productPromotable"])

    def test_command_has_exact_binary_disposable_path_and_independent_metal_observer_modes(self):
        args = self.arguments(); spec, _ = self.fixture()
        expected = {"build": {"sourceSha": "a" * 40}}
        command = workload_command(args, self.root, args.output, expected, spec)
        self.assertIn("-PmetallumJar=" + str(args.jar), command)
        self.assertIn("-PclientRunDir=" + str(args.output / "client-instance"), command)
        self.assertIn("-PtrialBackend=metal3", command)
        self.assertIn("-PframeEvidenceMode=timing", command)
        self.assertNotIn("shell", command)
        args.backend = "metal4"; args.frame_evidence = "off"
        command = workload_command(args, self.root, args.output, expected, spec)
        self.assertIn("-PtrialBackend=metal4", command)
        self.assertIn("-PframeEvidenceMode=off", command)

    def test_physical_lock_rejects_concurrent_independent_open_but_allows_a_declared_child(self):
        with patch.dict(os.environ, {}, clear=True):
            with runner_lock(self.root) as lock:
                with self.assertRaises(BlockingIOError): runner_lock(self.root)
                with patch.dict(os.environ, {"METALLUM_FRAME_LOCK_FD": str(lock.fileno())}):
                    with runner_lock(self.root): pass
                with self.assertRaises(BlockingIOError): runner_lock(self.root)
            with runner_lock(self.root): pass


if __name__ == "__main__": unittest.main()
