#!/usr/bin/env python3
"""Focused tests for strict measurement-window normalization."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import normalize_unified_trial as normalizer  # noqa: E402


def write_trial(report: dict) -> Path:
    root = Path(tempfile.mkdtemp())
    (root / "exit-status.txt").write_text("0\n", encoding="utf-8")
    report_path = root / "artifacts" / "validation" / "native-fullscreen-baseline.json"
    report_path.parent.mkdir(parents=True)
    report_path.write_text(json.dumps(report, sort_keys=True), encoding="utf-8")
    return root


def valid_report() -> dict:
    return {
        "measuredFrameIntervals": 3,
        "measuredGpuFrames": 3,
        "measuredGpuCommandBuffers": 3,
        "sourceFpsFromP50": 40.0,
        "gpuP50Milliseconds": 3.0,
        "gpuSubmissionSamples": [
            {"submitIndex": 100, "windowId": 4, "frameId": 40, "gpuStartTime": 1.000, "gpuEndTime": 1.002},
            {"submitIndex": 101, "windowId": 4, "frameId": 41, "gpuStartTime": 2.000, "gpuEndTime": 2.003},
            {"submitIndex": 102, "windowId": 4, "frameId": 42, "gpuStartTime": 3.000, "gpuEndTime": 3.004},
        ],
        "cpuRenderEncodeFrameMilliseconds": {"samples": 3, "p50Milliseconds": 24.0},
        "nativeEncoderCountsPerMeasuredFrame": {
            "measuredFrames": 3,
            "renderPerFrame": 6.0,
            "blitPerFrame": 2.0,
        },
        "measurementWindow": {
            "id": 4,
            "startFrameInclusive": 40,
            "endFrameExclusive": 43,
            "completedFrames": 3,
            "firstSubmitIndexInclusive": 100,
            "lastSubmitIndexExclusive": 103,
            "gpuSubmissionIdentityComplete": True,
            "nativeEncoderIdentityComplete": True,
        },
    }


class MeasurementWindowTests(unittest.TestCase):
    def test_valid_window_enables_all_windowed_metrics(self) -> None:
        root = write_trial(valid_report())
        result = normalizer.normalize(root)
        self.assertTrue(result["complete"])
        for name in (
            "gpu_frame_time_ms_median",
            "cpu_render_encode_time_ms_median",
            "native_encoder_count_per_frame_median",
        ):
            self.assertTrue(result["metrics"][name]["available"])

    def test_legacy_report_is_diagnostic_only(self) -> None:
        report = valid_report()
        del report["measurementWindow"]
        root = write_trial(report)
        result = normalizer.normalize(root)
        self.assertFalse(result["complete"])
        for name in (
            "gpu_frame_time_ms_median",
            "cpu_render_encode_time_ms_median",
            "native_encoder_count_per_frame_median",
        ):
            self.assertFalse(result["metrics"][name]["available"])

    def test_unidentified_encoder_totals_cannot_be_accepted(self) -> None:
        report = valid_report()
        report["measurementWindow"]["nativeEncoderIdentityComplete"] = False
        report["nativeEncoderCountsPerMeasuredFrame"]["renderPerFrame"] = 600.0
        root = write_trial(report)
        result = normalizer.normalize(root)
        self.assertFalse(result["complete"])
        self.assertTrue(result["metrics"]["gpu_frame_time_ms_median"]["available"])
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_window_frame_bounds_are_checked(self) -> None:
        report = valid_report()
        report["measurementWindow"]["endFrameExclusive"] = 339
        root = write_trial(report)
        result = normalizer.normalize(root)
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["gpu_frame_time_ms_median"]["available"])
        self.assertFalse(result["metrics"]["cpu_render_encode_time_ms_median"]["available"])

    def test_cpu_samples_must_equal_completed_frames(self) -> None:
        report = valid_report()
        report["cpuRenderEncodeFrameMilliseconds"]["samples"] = 2
        root = write_trial(report)
        result = normalizer.normalize(root)
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["cpu_render_encode_time_ms_median"]["available"])
        self.assertTrue(result["metrics"]["gpu_frame_time_ms_median"]["available"])

    def test_multiple_gpu_submissions_per_frame_are_valid(self) -> None:
        report = valid_report()
        report["measuredGpuCommandBuffers"] = 5
        report["measurementWindow"]["lastSubmitIndexExclusive"] = 105
        report["gpuSubmissionSamples"].extend([
            {"submitIndex": 103, "windowId": 4, "frameId": 40, "gpuStartTime": 4.000, "gpuEndTime": 4.0005},
            {"submitIndex": 104, "windowId": 4, "frameId": 41, "gpuStartTime": 5.000, "gpuEndTime": 5.0005},
        ])
        report["gpuP50Milliseconds"] = 3.5
        root = write_trial(report)
        result = normalizer.normalize(root)
        self.assertTrue(result["metrics"]["gpu_frame_time_ms_median"]["available"])
        self.assertEqual(3, result["metrics"]["gpu_frame_time_ms_median"]["sample_count"])

    def test_gpu_value_must_match_nearest_rank_of_raw_frame_totals(self) -> None:
        report = valid_report()
        report["gpuP50Milliseconds"] = 3.001
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["gpu_frame_time_ms_median"]["available"])

    def test_extreme_declared_bounds_are_rejected_without_allocating_the_range(self) -> None:
        report = valid_report()
        report["measurementWindow"]["lastSubmitIndexExclusive"] = 2 ** 63 - 1
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["complete"])

    def test_missing_gpu_submission_identity_is_rejected(self) -> None:
        report = valid_report()
        report["measurementWindow"]["gpuSubmissionIdentityComplete"] = False
        report["measuredGpuCommandBuffers"] = 5
        report["measurementWindow"]["lastSubmitIndexExclusive"] = 105
        report["gpuSubmissionSamples"].extend([
            {"submitIndex": 103, "windowId": 4, "frameId": 40, "gpuStartTime": 4.000, "gpuEndTime": 4.0005},
            {"submitIndex": 104, "windowId": 4, "frameId": 41, "gpuStartTime": 5.000, "gpuEndTime": 5.0005},
        ])
        report["gpuP50Milliseconds"] = 3.5
        root = write_trial(report)
        result = normalizer.normalize(root)
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["gpu_frame_time_ms_median"]["available"])

    def test_same_count_with_duplicate_submit_is_rejected(self) -> None:
        report = valid_report()
        report["gpuSubmissionSamples"][2]["submitIndex"] = 101
        root = write_trial(report)
        result = normalizer.normalize(root)
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["gpu_frame_time_ms_median"]["available"])

    def test_warmup_frame_contamination_is_rejected(self) -> None:
        report = valid_report()
        report["gpuSubmissionSamples"][2]["frameId"] = 39
        root = write_trial(report)
        result = normalizer.normalize(root)
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["gpu_frame_time_ms_median"]["available"])


if __name__ == "__main__":
    unittest.main()
