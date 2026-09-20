#!/usr/bin/env python3
"""Focused hand-built tests for the logical attachment-action estimator."""

from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ImportError(path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


raw = _load("verify_native_attachment_facts", ROOT / "scripts/agent/verify_native_attachment_facts.py")
estimator = _load("estimate_attachment_actions", ROOT / "scripts/agent/estimate_attachment_actions.py")


def _report() -> dict:
    report = copy.deepcopy(raw._fixture())
    rows = report["nativeAttachmentLedger"]["rows"]
    # Marker dimensions are intentionally zero. The estimator must use the
    # retained attachment dimensions for the ordinary 2D descriptor.
    for row in rows:
        # Marker zero dimensions mean unconstrained; all child rows still carry
        # the same marker tuple so the estimator can audit descriptor shape.
        row.update(renderTargetWidth=0, renderTargetHeight=0, renderTargetArrayLength=0)

    def child(sequence: int, aspect: int):
        return next(row for row in rows if row["encoderSequence"] == sequence and row["aspect"] == aspect)

    # Frame 100: RGBA8 clear/store (24 store bytes), Depth32 load/discard
    # (24 load plus deferred-discard bytes), Stencil8 clear/dontCare, and a
    # BGRA8 load/store 4x1 (16 load + 16 store).
    color1 = child(1, 0)
    color1.update(pixelFormat=70, width=2, height=3, depth=1, arrayLength=1,
                  textureType=2, sampleCount=1, storageMode=2, level=0, slice=0,
                  depthPlane=0, loadAction=2, initialStoreAction=4, finalStoreAction=1)
    depth1 = child(1, 1)
    depth1.update(pixelFormat=252, width=2, height=3, depth=1, arrayLength=1,
                  textureType=2, sampleCount=1, storageMode=2, level=0, slice=0,
                  depthPlane=0, loadAction=1, initialStoreAction=4, finalStoreAction=0)

    # Add stencil to the first descriptor and use a known dontCare initial
    # action so deferredDiscardBytes is exercised separately by depth.
    rows[0]["slot"] = 0x301
    stencil = copy.deepcopy(depth1)
    stencil.update(aspect=2, slot=0, pixelFormat=253, loadAction=2,
                   initialStoreAction=0, finalStoreAction=0)
    rows.insert(3, stencil)

    color2 = child(2, 0)
    color2.update(pixelFormat=80, width=4, height=1, depth=1, arrayLength=1,
                  textureType=2, sampleCount=1, storageMode=2, level=0, slice=0,
                  depthPlane=0, loadAction=1, initialStoreAction=4, finalStoreAction=1)

    # Frame 101: RGBA8 2x5 load/store, giving a second exact per-frame total.
    color3 = child(3, 0)
    color3.update(pixelFormat=70, width=2, height=5, depth=1, arrayLength=1,
                  textureType=2, sampleCount=1, storageMode=2, level=0, slice=0,
                  depthPlane=0, loadAction=1, initialStoreAction=4, finalStoreAction=1)
    report["nativeAttachmentLedger"]["rowCount"] = len(rows)
    report["nativeAttachmentLedger"]["createdRenderEncoders"] = 3
    return report


class EstimateAttachmentActionsTest(unittest.TestCase):
    def estimate(self, report: dict):
        result, errors = estimator.estimate_attachment_actions(report)
        self.assertEqual([], errors, errors)
        self.assertIsNotNone(result)
        return result

    def test_supported_formats_actions_frames_and_median(self):
        result = self.estimate(_report())
        self.assertEqual(1, result["schemaVersion"])
        self.assertEqual("native-attachment-actions-v1", result["source"])
        self.assertEqual("main-queue-logical-attachment-action-bytes", result["scope"])
        self.assertTrue(result["complete"])
        self.assertIn("Logical", result["definition"])
        self.assertIn("not measured GPU/DRAM traffic", result["definition"])
        self.assertFalse(result["physicalBandwidthMeasured"])
        frames = {frame["frameId"]: frame for frame in result["frames"]}
        self.assertEqual({100, 101}, set(frames))
        self.assertEqual(
            {"frameId": 100, "loadBytes": 40, "storeBytes": 40,
             "resolveBytes": 0, "deferredDiscardBytes": 24, "totalBytes": 80},
            frames[100],
        )
        self.assertEqual(
            {"frameId": 101, "loadBytes": 40, "storeBytes": 40,
             "resolveBytes": 0, "deferredDiscardBytes": 0, "totalBytes": 80},
            frames[101],
        )
        self.assertEqual(160, result["totals"]["totalBytes"])
        self.assertEqual(24, result["totals"]["deferredDiscardBytes"])
        self.assertEqual(80, result["medianBytesPerFrame"])

    def test_resolve_bytes_are_separate_from_store_bytes(self):
        report = _report()
        row = next(row for row in report["nativeAttachmentLedger"]["rows"]
                   if row["encoderSequence"] == 3 and row["aspect"] == 0)
        row.update(finalStoreAction=2, textureType=4, sampleCount=4,
                   resolvePixelFormat=70, resolveWidth=2,
                   resolveHeight=5, resolveDepth=1, resolveArrayLength=1,
                   resolveTextureType=2, resolveSampleCount=1, resolveStorageMode=2,
                   resolveLevel=0, resolveSlice=0, resolveDepthPlane=0)
        result = self.estimate(report)
        frame = next(frame for frame in result["frames"] if frame["frameId"] == 101)
        self.assertEqual(160, frame["loadBytes"])
        self.assertEqual(0, frame["storeBytes"])
        self.assertEqual(40, frame["resolveBytes"])
        self.assertEqual(200, frame["totalBytes"])

    def assert_rejected(self, report: dict, needle: str | None = None):
        result, errors = estimator.estimate_attachment_actions(report)
        self.assertIsNone(result)
        self.assertTrue(errors)
        if needle:
            self.assertTrue(any(needle in error for error in errors), errors)

    def test_missing_ledger_is_honest_unavailable(self):
        report = _report()
        del report["nativeAttachmentLedger"]
        self.assertEqual((None, []), estimator.estimate_attachment_actions(report))

    def test_zero_render_frame_is_retained_as_zero_bytes(self):
        report = _report()
        report["measurementWindow"].update(endFrameExclusive=103, completedFrames=3,
                                            lastSubmitIndexExclusive=14)
        report["measuredGpuFrames"] = 3
        report["measuredGpuCommandBuffers"] = 4
        report["gpuSubmissionSamples"].append({"windowId": 7, "frameId": 102, "submitIndex": 13})
        report["nativeEncoderLedger"]["rows"].append({
            "windowId": 7, "frameId": 102, "submitIndex": 13, "backend": 3,
            "attempted": 1, "created": 1, "ended": 1, "renderCreated": 0,
            "blitCreated": 1, "computeCreated": 0, "createFailures": 0,
            "unsupportedEncodes": 0, "invalidEvents": 0,
        })
        report["nativeEncoderLedger"]["rowCount"] = 4
        result = self.estimate(report)
        frame = next(frame for frame in result["frames"] if frame["frameId"] == 102)
        self.assertEqual({"frameId": 102, "loadBytes": 0, "storeBytes": 0,
                          "resolveBytes": 0, "deferredDiscardBytes": 0, "totalBytes": 0}, frame)
        self.assertEqual(80, result["medianBytesPerFrame"])

    def test_array_layers_are_counted_without_inventing_extra_slices(self):
        report = _report()
        marker = next(row for row in report["nativeAttachmentLedger"]["rows"]
                      if row["encoderSequence"] == 2 and row["aspect"] == -1)
        child = next(row for row in report["nativeAttachmentLedger"]["rows"]
                     if row["encoderSequence"] == 2 and row["aspect"] == 0)
        marker["renderTargetArrayLength"] = 2
        child["renderTargetArrayLength"] = 2
        child["arrayLength"] = 2
        child["textureType"] = 3
        child["slice"] = 0
        result = self.estimate(report)
        frame = next(frame for frame in result["frames"] if frame["frameId"] == 100)
        self.assertEqual(56, frame["loadBytes"])
        self.assertEqual(56, frame["storeBytes"])
        self.assertEqual(112, frame["totalBytes"])

    def test_nonlayered_array_selected_slice_counts_one_layer(self):
        report = _report()
        child = next(row for row in report["nativeAttachmentLedger"]["rows"]
                     if row["encoderSequence"] == 2 and row["aspect"] == 0)
        child["arrayLength"] = 2
        child["textureType"] = 3
        child["slice"] = 1
        result = self.estimate(report)
        frame = next(frame for frame in result["frames"] if frame["frameId"] == 100)
        self.assertEqual(80, frame["totalBytes"])

        report = _report()
        child = next(row for row in report["nativeAttachmentLedger"]["rows"]
                     if row["encoderSequence"] == 2 and row["aspect"] == 0)
        child["arrayLength"] = 2
        child["textureType"] = 3
        child["slice"] = 2
        self.assert_rejected(report, "outside the texture array")

    def test_memoryless_clear_discard_has_no_external_payload(self):
        report = _report()
        target = next(row for row in report["nativeAttachmentLedger"]["rows"]
                      if row["encoderSequence"] == 1 and row["aspect"] == 0)
        target.update(storageMode=3, loadAction=2, initialStoreAction=0, finalStoreAction=0)
        result = self.estimate(report)
        frame = next(frame for frame in result["frames"] if frame["frameId"] == 100)
        self.assertEqual(40, frame["loadBytes"])
        self.assertEqual(16, frame["storeBytes"])

    def test_memoryless_resolve_destination_is_rejected(self):
        report = _report()
        target = next(row for row in report["nativeAttachmentLedger"]["rows"]
                      if row["encoderSequence"] == 3 and row["aspect"] == 0)
        target.update(finalStoreAction=2, textureType=4, sampleCount=4,
                      resolvePixelFormat=70, resolveWidth=2, resolveHeight=5,
                      resolveDepth=1, resolveArrayLength=1, resolveTextureType=2,
                      resolveSampleCount=1, resolveStorageMode=3,
                      resolveLevel=0, resolveSlice=0, resolveDepthPlane=0)
        self.assert_rejected(report, "externally backed")

    def test_memoryless_msaa_source_clear_resolve_has_only_destination_bytes(self):
        report = _report()
        target = next(row for row in report["nativeAttachmentLedger"]["rows"]
                      if row["encoderSequence"] == 3 and row["aspect"] == 0)
        target.update(finalStoreAction=2, textureType=4, sampleCount=4, storageMode=3,
                      loadAction=2, resolvePixelFormat=70, resolveWidth=2,
                      resolveHeight=5, resolveDepth=1, resolveArrayLength=1,
                      resolveTextureType=2, resolveSampleCount=1, resolveStorageMode=2,
                      resolveLevel=0, resolveSlice=0, resolveDepthPlane=0)
        result = self.estimate(report)
        frame = next(frame for frame in result["frames"] if frame["frameId"] == 101)
        self.assertEqual(0, frame["loadBytes"])
        self.assertEqual(0, frame["storeBytes"])
        self.assertEqual(40, frame["resolveBytes"])
        self.assertEqual(40, frame["totalBytes"])

    def test_store_and_resolve_counts_source_store_and_destination_resolve(self):
        report = _report()
        target = next(row for row in report["nativeAttachmentLedger"]["rows"]
                      if row["encoderSequence"] == 3 and row["aspect"] == 0)
        target.update(finalStoreAction=3, textureType=4, sampleCount=4,
                      resolvePixelFormat=70, resolveWidth=2, resolveHeight=5,
                      resolveDepth=1, resolveArrayLength=1, resolveTextureType=2,
                      resolveSampleCount=1, resolveStorageMode=2,
                      resolveLevel=0, resolveSlice=0, resolveDepthPlane=0)
        result = self.estimate(report)
        frame = next(frame for frame in result["frames"] if frame["frameId"] == 101)
        self.assertEqual(160, frame["loadBytes"])
        self.assertEqual(160, frame["storeBytes"])
        self.assertEqual(40, frame["resolveBytes"])
        self.assertEqual(360, frame["totalBytes"])

    def test_packed_depth_stencil_formats_are_supported(self):
        report = _report()
        depth = next(row for row in report["nativeAttachmentLedger"]["rows"]
                     if row["encoderSequence"] == 1 and row["aspect"] == 1)
        stencil = next(row for row in report["nativeAttachmentLedger"]["rows"]
                       if row["encoderSequence"] == 1 and row["aspect"] == 2)
        depth["pixelFormat"] = 260
        stencil["pixelFormat"] = 260
        stencil.update(loadAction=1, initialStoreAction=4, finalStoreAction=1)
        result = self.estimate(report)
        frame = next(frame for frame in result["frames"] if frame["frameId"] == 100)
        self.assertEqual(46, frame["loadBytes"])
        self.assertEqual(46, frame["storeBytes"])
        self.assertEqual(92, frame["totalBytes"])

    def test_unknown_final_store_and_custom_depth_store_are_rejected(self):
        for final_store in (4, 5):
            report = _report()
            target = next(row for row in report["nativeAttachmentLedger"]["rows"] if row["aspect"] == 0)
            target["finalStoreAction"] = final_store
            self.assert_rejected(report)

    def test_positive_explicit_target_area_can_be_smaller_than_texture(self):
        report = _report()
        marker = next(row for row in report["nativeAttachmentLedger"]["rows"]
                      if row["encoderSequence"] == 2 and row["aspect"] == -1)
        child = next(row for row in report["nativeAttachmentLedger"]["rows"]
                     if row["encoderSequence"] == 2 and row["aspect"] == 0)
        marker.update(renderTargetWidth=2, renderTargetHeight=1)
        child.update(renderTargetWidth=2, renderTargetHeight=1)
        result = self.estimate(report)
        frame = next(frame for frame in result["frames"] if frame["frameId"] == 100)
        self.assertEqual(64, frame["totalBytes"])

    def test_validator_rejects_forged_row_count_and_mask_coverage(self):
        report = _report()
        report["nativeAttachmentLedger"]["rows"].pop(1)
        report["nativeAttachmentLedger"]["rowCount"] -= 1
        self.assert_rejected(report, "mask")

    def test_validator_rejects_missing_encoder_group_even_with_forged_counts(self):
        report = _report()
        report["nativeAttachmentLedger"]["rows"] = [
            row for row in report["nativeAttachmentLedger"]["rows"]
            if row["encoderSequence"] != 3
        ]
        report["nativeAttachmentLedger"]["rowCount"] = len(report["nativeAttachmentLedger"]["rows"])
        report["nativeAttachmentLedger"]["createdRenderEncoders"] = 2
        self.assert_rejected(report, "renderCreated")

    def test_overflow_and_unsupported_format_shape_are_rejected(self):
        report = _report()
        for row in report["nativeAttachmentLedger"]["rows"]:
            if row["encoderSequence"] == 1 and row["aspect"] != -1:
                row["width"] = (1 << 63) - 1
                row["height"] = 1
        self.assert_rejected(report, "logical byte product")

        for field, value, needle in (
            ("pixelFormat", 9999, "pixel"),
            ("pixelFormat", 255, "pixel"),
            ("sampleCount", 2, "sample"),
            ("storageMode", 3, "memoryless"),
            ("textureType", 5, "texture"),
        ):
            malformed = _report()
            target = next(item for item in malformed["nativeAttachmentLedger"]["rows"] if item["aspect"] == 0)
            target[field] = value
            self.assert_rejected(malformed, needle)

    def test_bad_resolve_shape_and_cross_backend_are_rejected(self):
        report = _report()
        target = next(row for row in report["nativeAttachmentLedger"]["rows"]
                      if row["encoderSequence"] == 3 and row["aspect"] == 0)
        target.update(finalStoreAction=2, resolvePixelFormat=80, resolveWidth=0,
                      resolveHeight=5, resolveDepth=1, resolveArrayLength=1,
                      resolveTextureType=2, resolveSampleCount=1, resolveStorageMode=2)
        self.assert_rejected(report, "resolve")

        report = _report()
        marker = next(row for row in report["nativeAttachmentLedger"]["rows"] if row["aspect"] == -1)
        marker["backend"] = 4
        self.assert_rejected(report, "backend")

        report = _report()
        child = next(row for row in report["nativeAttachmentLedger"]["rows"]
                     if row["encoderSequence"] == 1 and row["aspect"] == 1)
        child["renderTargetWidth"] = 1
        self.assert_rejected(report, "render area")

    def test_boolean_and_duplicate_rows_are_rejected(self):
        report = _report()
        report["nativeAttachmentLedger"]["enabled"] = 1
        self.assert_rejected(report, "enabled")

        report = _report()
        duplicate = copy.deepcopy(next(row for row in report["nativeAttachmentLedger"]["rows"] if row["aspect"] == 0))
        report["nativeAttachmentLedger"]["rows"].append(duplicate)
        report["nativeAttachmentLedger"]["rowCount"] += 1
        self.assert_rejected(report, "duplicate")


if __name__ == "__main__":
    unittest.main()
