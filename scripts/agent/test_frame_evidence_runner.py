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
        }), stderr="")

    def run_completed(self, client, mode="timing"):
        self.verifier_calls = 0
        receipt = {"gameplay": {"status": "completed"}}
        runner.finalize_client_run(receipt, None, client, self.output, self.root,
                                   self.identity, mode, self.fake_verifier)
        return receipt

    def test_archive_written_during_client_wait_is_verified_after_wait(self):
        receipt = self.run_completed(DeferredClient(self.output, {
            "schemaVersion": 2, "archive": {"complete": True},
        }))
        self.assertEqual(self.verifier_calls, 1)
        self.assertEqual(receipt["frameEvidenceVerification"]["status"], "valid-observation")
        self.assertTrue((self.output / "frame-evidence-verification.json").is_file())

    def test_missing_or_invalid_final_archive_fails(self):
        with self.assertRaisesRegex(RuntimeError, "did not export"):
            self.run_completed(DeferredClient(self.output))
        with self.assertRaisesRegex(RuntimeError, "bounded archive schema"):
            self.run_completed(DeferredClient(self.output, {"schemaVersion": 1}))

    def test_off_mode_does_not_require_archive_or_invoke_verifier(self):
        receipt = self.run_completed(DeferredClient(self.output), mode="off")
        self.assertEqual(self.verifier_calls, 0)
        self.assertIsNone(receipt["frameEvidenceVerification"])


if __name__ == "__main__":
    unittest.main()
