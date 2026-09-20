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
from verify_native_attachment_facts import ATTACHMENT_FIELDS  # noqa: E402


def write_trial(report: dict) -> Path:
    root = Path(tempfile.mkdtemp())
    (root / "exit-status.txt").write_text("0\n", encoding="utf-8")
    report_path = root / "artifacts" / "validation" / "native-fullscreen-baseline.json"
    report_path.parent.mkdir(parents=True)
    report_path.write_text(json.dumps(report, sort_keys=True), encoding="utf-8")
    return root


def process_memory_fixture() -> dict:
    samples = [
        {"sequence": 0, "windowId": 4, "frameId": 40, "phase": "frame-begin",
         "beginOffsetNanos": 0, "endOffsetNanos": 10, "kernelStatus": 0,
         "returnedWordCount": 38, "residentBytes": 100, "physicalFootprintBytes": 200,
         "lifetimeResidentPeakBytes": 105},
        {"sequence": 1, "windowId": 4, "frameId": 40, "phase": "frame-end",
         "beginOffsetNanos": 10, "endOffsetNanos": 25, "kernelStatus": 0,
         "returnedWordCount": 38, "residentBytes": 120, "physicalFootprintBytes": 180,
         "lifetimeResidentPeakBytes": 125},
        {"sequence": 2, "windowId": 4, "frameId": 41, "phase": "frame-begin",
         "beginOffsetNanos": 25, "endOffsetNanos": 35, "kernelStatus": 0,
         "returnedWordCount": 38, "residentBytes": 110, "physicalFootprintBytes": 220,
         "lifetimeResidentPeakBytes": 125},
        {"sequence": 3, "windowId": 4, "frameId": 41, "phase": "frame-end",
         "beginOffsetNanos": 35, "endOffsetNanos": 50, "kernelStatus": 0,
         "returnedWordCount": 38, "residentBytes": 130, "physicalFootprintBytes": 210,
         "lifetimeResidentPeakBytes": 135},
        {"sequence": 4, "windowId": 4, "frameId": 42, "phase": "frame-begin",
         "beginOffsetNanos": 50, "endOffsetNanos": 60, "kernelStatus": 0,
         "returnedWordCount": 38, "residentBytes": 115, "physicalFootprintBytes": 240,
         "lifetimeResidentPeakBytes": 135},
        {"sequence": 5, "windowId": 4, "frameId": 42, "phase": "frame-end",
         "beginOffsetNanos": 60, "endOffsetNanos": 75, "kernelStatus": 0,
         "returnedWordCount": 38, "residentBytes": 140, "physicalFootprintBytes": 230,
         "lifetimeResidentPeakBytes": 145},
        {"sequence": 6, "windowId": 4, "frameId": 43, "phase": "window-drain",
         "beginOffsetNanos": 75, "endOffsetNanos": 85, "kernelStatus": 0,
         "returnedWordCount": 38, "residentBytes": 125, "physicalFootprintBytes": 190,
         "lifetimeResidentPeakBytes": 145},
    ]
    return {
        "schemaVersion": 1,
        "source": "mach_task_info(TASK_VM_INFO.resident_size)",
        "scope": "current-process",
        "samplingPolicy": "frame-boundaries-and-final-drain",
        "peakKind": "sampled-maximum",
        "windowId": 4,
        "firstFrame": 40,
        "endFrameExclusive": 43,
        "capacitySamples": 7,
        "droppedSamples": 0,
        "failedSamples": 0,
        "invalidEvents": 0,
        "sampleCount": 7,
        "complete": True,
        "status": "complete-sampled-process-rss",
        "peakResidentBytes": 140,
        "peakPhysicalFootprintBytes": 240,
        "lifetimeResidentPeakBytesLast": 145,
        "totalProbeNanos": 85,
        "maxProbeNanos": 15,
        "endOffsetNanos": 85,
        "samples": samples,
    }


def valid_report() -> dict:
    encoder_rows = [
        {
            "windowId": 4,
            "frameId": 40 + index,
            "submitIndex": 100 + index,
            "backend": 3 if index != 1 else 4,
            "attempted": 3,
            "created": 3,
            "ended": 3,
            "renderCreated": 2,
            "blitCreated": 1,
            "computeCreated": 0,
            "createFailures": 0,
            "unsupportedEncodes": 0,
            "invalidEvents": 0,
        }
        for index in range(3)
    ]
    return {
        "mode": "native-metalfx-off",
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
            "status": "complete-main-queue-native-encoders",
            "complete": True,
            "measuredFrames": 3,
            "renderTotal": 6,
            "blitTotal": 3,
            "computeTotal": 0,
            "renderPerFrame": 2.0,
            "blitPerFrame": 1.0,
            "computePerFrame": 0.0,
            "p50PerFrame": 3.0,
        },
        "nativeEncoderLedger": {
            "schemaVersion": 1,
            "enabled": True,
            "capacityRows": 3,
            "droppedRows": 0,
            "invalidEvents": 0,
            "activeCommandBuffers": 0,
            "activeEncoders": 0,
            "rowCount": 3,
            "scope": "main-queue-native-encoders",
            "rows": encoder_rows,
        },
        "metalFxOffDiagnostics": {
            "modeOff": True,
            "allWorkEliminated": True,
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
        "processMemory": process_memory_fixture(),
    }


def with_attachment_facts(report: dict) -> dict:
    rows = []
    for index, encoder in enumerate(report["nativeEncoderLedger"]["rows"]):
        for offset in range(2):
            marker = dict.fromkeys(ATTACHMENT_FIELDS, 0)
            marker.update({key: encoder[key] for key in ("windowId", "frameId", "submitIndex", "backend")})
            marker.update(encoderSequence=index * 2 + offset + 1, aspect=-1, slot=1, ended=1)
            child = dict(marker)
            child.update(aspect=0, slot=0, pixelFormat=70, width=2, height=index + 1,
                         depth=1, arrayLength=1, textureType=2, sampleCount=1,
                         storageMode=2, loadAction=1, initialStoreAction=4, finalStoreAction=1)
            rows.extend((marker, child))
    report["nativeAttachmentLedger"] = {
        "schemaVersion": 1, "enabled": True, "capacityRows": len(rows),
        "droppedRows": 0, "invalidEvents": 0, "activeRenderEncoders": 0,
        "createdRenderEncoders": 6, "rowCount": len(rows),
        "scope": "main-queue-render-attachment-actions", "rows": rows,
    }
    return report


class MeasurementWindowTests(unittest.TestCase):
    def test_attachment_action_metric_is_recomputed_per_frame(self) -> None:
        result = normalizer.normalize(write_trial(with_attachment_facts(valid_report())))
        self.assertTrue(result["complete"], result["identity_errors"])
        metric = result["metrics"]["render_pass_store_load_bytes_estimate_median"]
        self.assertTrue(metric["available"])
        # 2 encoders * 2 pixels wide * 2 rows * 4 bytes * (load + store).
        self.assertEqual(64, metric["median"])
        self.assertFalse(metric["source"]["physicalBandwidthMeasured"])

    def test_missing_attachment_facts_remain_unavailable(self) -> None:
        result = normalizer.normalize(write_trial(valid_report()))
        self.assertTrue(result["complete"])
        self.assertFalse(result["metrics"]["render_pass_store_load_bytes_estimate_median"]["available"])

    def test_incomplete_or_unsupported_attachment_facts_reject_trial(self) -> None:
        for mutate in (
            lambda ledger: ledger.update(droppedRows=1),
            lambda ledger: ledger["rows"][1].update(pixelFormat=255),
            lambda ledger: ledger["rows"][1].update(finalStoreAction=4),
        ):
            report = with_attachment_facts(valid_report())
            mutate(report["nativeAttachmentLedger"])
            result = normalizer.normalize(write_trial(report))
            self.assertFalse(result["complete"])
            self.assertFalse(result["metrics"]["render_pass_store_load_bytes_estimate_median"]["available"])
            self.assertTrue(result["source_summary"]["attachment_action_errors"])

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
        self.assertTrue(result["metrics"]["peak_resident_memory_bytes"]["available"])
        self.assertEqual(140, result["metrics"]["peak_resident_memory_bytes"]["median"])
        self.assertEqual(
            "sampled-maximum",
            result["metrics"]["peak_resident_memory_bytes"]["source"]["peak_kind"],
        )

    def test_missing_process_memory_remains_unavailable(self) -> None:
        report = valid_report()
        del report["processMemory"]
        result = normalizer.normalize(write_trial(report))
        self.assertTrue(result["complete"])
        metric = result["metrics"]["peak_resident_memory_bytes"]
        self.assertFalse(metric["available"])
        self.assertIn("missing", metric["reason"])

    def test_process_memory_foreign_window_is_rejected(self) -> None:
        report = valid_report()
        report["processMemory"]["windowId"] = 99
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["peak_resident_memory_bytes"]["available"])

    def test_process_memory_wrong_phase_is_rejected(self) -> None:
        report = valid_report()
        report["processMemory"]["samples"][1]["phase"] = "window-drain"
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["peak_resident_memory_bytes"]["available"])

    def test_process_memory_missing_frame_sample_is_rejected(self) -> None:
        report = valid_report()
        report["processMemory"]["samples"].pop(2)
        report["processMemory"]["sampleCount"] = 6
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["peak_resident_memory_bytes"]["available"])

    def test_process_memory_wrong_summary_is_rejected(self) -> None:
        report = valid_report()
        report["processMemory"]["peakResidentBytes"] = 999
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["peak_resident_memory_bytes"]["available"])

    def test_process_memory_drop_and_failed_samples_are_rejected(self) -> None:
        for field in ("droppedSamples", "failedSamples", "invalidEvents"):
            report = valid_report()
            report["processMemory"][field] = 1
            result = normalizer.normalize(write_trial(report))
            self.assertFalse(result["complete"], field)
            self.assertFalse(result["metrics"]["peak_resident_memory_bytes"]["available"])

    def test_process_memory_int64_overflow_is_rejected(self) -> None:
        report = valid_report()
        report["processMemory"]["endOffsetNanos"] = 1 << 63
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["peak_resident_memory_bytes"]["available"])

    def test_process_memory_time_order_is_rejected(self) -> None:
        report = valid_report()
        report["processMemory"]["samples"][3]["beginOffsetNanos"] = 20
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["peak_resident_memory_bytes"]["available"])

    def test_process_memory_huge_window_is_rejected_without_expanding_expected_rows(self) -> None:
        report = valid_report()
        report["measurementWindow"].update({
            "endFrameExclusive": (1 << 63) - 1,
            "completedFrames": (1 << 63) - 1 - 40,
        })
        report["processMemory"].update({
            "endFrameExclusive": (1 << 63) - 1,
            "sampleCount": (1 << 64) - 79,
            "capacitySamples": 65536,
        })
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["peak_resident_memory_bytes"]["available"])

    def test_process_memory_invalid_local_bounds_are_rejected(self) -> None:
        report = valid_report()
        report["processMemory"]["firstFrame"] = -1
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["peak_resident_memory_bytes"]["available"])

    def test_lifetime_peak_is_not_substituted_for_window_rss_peak(self) -> None:
        report = valid_report()
        report["processMemory"]["lifetimeResidentPeakBytesLast"] = 140
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["complete"])
        self.assertFalse(result["metrics"]["peak_resident_memory_bytes"]["available"])

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
            "peak_resident_memory_bytes",
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

    def test_timestamp_absence_does_not_invalidate_complete_encoder_ledger(self) -> None:
        report = valid_report()
        report.pop("gpuNativeEncoders", None)
        report.pop("nativeEncoderTimingSamples", None)
        result = normalizer.normalize(write_trial(report))
        self.assertTrue(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_missing_encoder_row_is_rejected(self) -> None:
        report = valid_report()
        report["nativeEncoderLedger"]["rows"].pop()
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_duplicate_encoder_identity_is_rejected(self) -> None:
        report = valid_report()
        report["nativeEncoderLedger"]["rows"][2] = dict(report["nativeEncoderLedger"]["rows"][1])
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_bad_encoder_identity_is_rejected(self) -> None:
        report = valid_report()
        report["nativeEncoderLedger"]["rows"][0]["windowId"] = 99
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_ledger_overflow_is_rejected(self) -> None:
        report = valid_report()
        report["nativeEncoderLedger"]["droppedRows"] = 1
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_unended_encoder_is_rejected(self) -> None:
        report = valid_report()
        report["nativeEncoderLedger"]["rows"][1]["ended"] = 2
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_opaque_encoder_is_rejected(self) -> None:
        report = valid_report()
        report["nativeEncoderLedger"]["rows"][1]["unsupportedEncodes"] = 1
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_failed_encoder_create_is_rejected(self) -> None:
        report = valid_report()
        report["nativeEncoderLedger"]["rows"][1]["createFailures"] = 1
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_wrong_encoder_summary_is_rejected(self) -> None:
        report = valid_report()
        report["nativeEncoderCountsPerMeasuredFrame"]["p50PerFrame"] = 99.0
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_metalfx_work_gate_is_required(self) -> None:
        report = valid_report()
        report["metalFxOffDiagnostics"]["allWorkEliminated"] = False
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_zero_encoder_command_buffers_are_valid(self) -> None:
        report = valid_report()
        for row in report["nativeEncoderLedger"]["rows"]:
            for field in (
                "attempted", "created", "ended", "renderCreated", "blitCreated", "computeCreated",
            ):
                row[field] = 0
        report["nativeEncoderCountsPerMeasuredFrame"].update({
            "renderTotal": 0,
            "blitTotal": 0,
            "computeTotal": 0,
            "renderPerFrame": 0.0,
            "blitPerFrame": 0.0,
            "computePerFrame": 0.0,
            "p50PerFrame": 0.0,
        })
        result = normalizer.normalize(write_trial(report))
        self.assertTrue(result["metrics"]["native_encoder_count_per_frame_median"]["available"])
        self.assertEqual(0.0, result["metrics"]["native_encoder_count_per_frame_median"]["median"])

    def test_maximum_ledger_capacity_is_valid(self) -> None:
        report = valid_report()
        report["nativeEncoderLedger"]["capacityRows"] = 65536
        result = normalizer.normalize(write_trial(report))
        self.assertTrue(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_capacity_zero_or_above_native_limit_is_rejected(self) -> None:
        for capacity in (0, 65537):
            report = valid_report()
            report["nativeEncoderLedger"]["capacityRows"] = capacity
            result = normalizer.normalize(write_trial(report))
            self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_out_of_range_int64_identity_is_rejected(self) -> None:
        report = valid_report()
        report["nativeEncoderLedger"]["rows"][0]["submitIndex"] = 1 << 63
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_encoder_kind_sum_overflow_is_rejected(self) -> None:
        report = valid_report()
        row = report["nativeEncoderLedger"]["rows"][0]
        row.update({
            "attempted": (1 << 63) - 1,
            "created": (1 << 63) - 1,
            "ended": (1 << 63) - 1,
            "renderCreated": (1 << 63) - 1,
            "blitCreated": 1,
            "computeCreated": 0,
        })
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_frame_and_aggregate_sum_overflow_is_rejected(self) -> None:
        report = valid_report()
        first = report["nativeEncoderLedger"]["rows"][0]
        second = report["nativeEncoderLedger"]["rows"][1]
        first.update({"attempted": (1 << 63) - 1, "created": (1 << 63) - 1,
                      "ended": (1 << 63) - 1, "renderCreated": (1 << 63) - 1,
                      "blitCreated": 0, "computeCreated": 0})
        second.update({"attempted": (1 << 63) - 1, "created": (1 << 63) - 1,
                       "ended": (1 << 63) - 1, "renderCreated": (1 << 63) - 1,
                       "blitCreated": 0, "computeCreated": 0})
        result = normalizer.normalize(write_trial(report))
        self.assertFalse(result["metrics"]["native_encoder_count_per_frame_median"]["available"])

    def test_mixed_backends_and_multiple_encoders_per_frame_are_identity_bound(self) -> None:
        report = valid_report()
        extra_samples = [
            {"submitIndex": 103, "windowId": 4, "frameId": 40, "gpuStartTime": 4.000, "gpuEndTime": 4.0005},
            {"submitIndex": 104, "windowId": 4, "frameId": 41, "gpuStartTime": 5.000, "gpuEndTime": 5.0005},
        ]
        report["gpuSubmissionSamples"].extend(extra_samples)
        report["measuredGpuCommandBuffers"] = 5
        report["measurementWindow"]["lastSubmitIndexExclusive"] = 105
        rows = report["nativeEncoderLedger"]["rows"]
        rows.extend([
            {
                "windowId": 4,
                "frameId": 40,
                "submitIndex": 103,
                "backend": 4,
                "attempted": 5,
                "created": 5,
                "ended": 5,
                "renderCreated": 3,
                "blitCreated": 1,
                "computeCreated": 1,
                "createFailures": 0,
                "unsupportedEncodes": 0,
                "invalidEvents": 0,
            },
            {
                "windowId": 4,
                "frameId": 41,
                "submitIndex": 104,
                "backend": 3,
                "attempted": 1,
                "created": 1,
                "ended": 1,
                "renderCreated": 0,
                "blitCreated": 1,
                "computeCreated": 0,
                "createFailures": 0,
                "unsupportedEncodes": 0,
                "invalidEvents": 0,
            },
        ])
        report["nativeEncoderLedger"]["capacityRows"] = 5
        report["nativeEncoderLedger"]["rowCount"] = 5
        report["nativeEncoderCountsPerMeasuredFrame"].update({
            "renderTotal": 9,
            "blitTotal": 5,
            "computeTotal": 1,
            "renderPerFrame": 3.0,
            "blitPerFrame": 5.0 / 3.0,
            "computePerFrame": 1.0 / 3.0,
            "p50PerFrame": 4.0,
        })
        result = normalizer.normalize(write_trial(report))
        self.assertTrue(result["metrics"]["native_encoder_count_per_frame_median"]["available"])
        self.assertEqual(4.0, result["metrics"]["native_encoder_count_per_frame_median"]["median"])


if __name__ == "__main__":
    unittest.main()
