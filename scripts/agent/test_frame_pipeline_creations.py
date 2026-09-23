"""Independent rejection tests for source-frame PSO diagnostic rows."""
import copy
import unittest
from verify_frame_evidence import verify_pipeline_creations


class PipelineCreationEvidenceTest(unittest.TestCase):
    def event(self):
        return {"nativeCall": "metallum_MTLDevice_makeRenderPipelineState",
                "validationPipelineId": "sha256:" + "a" * 64, "pipelineLocation": "minecraft:test",
                "creationKind": "attachment-variant", "durationNs": 20, "succeeded": True,
                "signature": {"colorFormats": ["RGBA8Unorm"], "depthFormat": "Depth32Float",
                              "stencilFormat": "Invalid", "sampleCount": 1}}

    def test_valid_and_failed_attempts_are_both_honest_diagnostics(self):
        for success in (False, True):
            event = self.event(); event["succeeded"] = success
            frame = {"pipelineCreations": [event]}; original = copy.deepcopy(frame)
            verify_pipeline_creations(frame, "diagnostic", 100)
            self.assertEqual(original, frame)

    def test_legacy_absence_remains_readable_but_null_is_not_an_empty_buffer(self):
        verify_pipeline_creations({}, "diagnostic", 100)
        verify_pipeline_creations({"pipelineCreations": []}, "timing", 100)
        with self.assertRaises(ValueError):
            verify_pipeline_creations({"pipelineCreations": None}, "diagnostic", 100)

    def test_timing_capture_cannot_claim_per_attempt_diagnostics(self):
        with self.assertRaises(ValueError):
            verify_pipeline_creations({"pipelineCreations": [self.event()]}, "timing", 100)

    def test_buffer_bound_is_enforced_without_truncation(self):
        verify_pipeline_creations({"pipelineCreations": [self.event()] * 64}, "diagnostic", 100)
        with self.assertRaises(ValueError):
            verify_pipeline_creations({"pipelineCreations": [self.event()] * 65}, "diagnostic", 100)

    def test_invalid_identity_kind_outcome_and_duration_are_rejected(self):
        mutations = {"nativeCall": "some_other_call", "validationPipelineId": "0x1234",
                     "pipelineLocation": " ", "creationKind": "cache-hit", "succeeded": 1,
                     "durationNs": 101}
        for key, value in mutations.items():
            with self.subTest(key=key):
                event = self.event(); event[key] = value
                with self.assertRaises(ValueError):
                    verify_pipeline_creations({"pipelineCreations": [event]}, "diagnostic", 100)
        for duration in (-1, True, 0.5):
            event = self.event(); event["durationNs"] = duration
            with self.assertRaises(ValueError):
                verify_pipeline_creations({"pipelineCreations": [event]}, "diagnostic", 100)

    def test_invalid_attachment_signatures_are_rejected(self):
        for key, value in (("sampleCount", 0), ("sampleCount", True), ("colorFormats", ["R8"] * 9),
                           ("colorFormats", "RGBA8Unorm"), ("depthFormat", ""), ("stencilFormat", 0)):
            with self.subTest(key=key, value=value):
                event = self.event(); event["signature"][key] = value
                with self.assertRaises(ValueError):
                    verify_pipeline_creations({"pipelineCreations": [event]}, "diagnostic", 100)


if __name__ == "__main__":
    unittest.main()
