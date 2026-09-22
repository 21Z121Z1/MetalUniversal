"""Behavior tests for the existing frame-evidence analyzer (no display simulation claim)."""
import math
import unittest
from verify_frame_evidence import presentation_statistics


class PresentationStatisticsTest(unittest.TestCase):
    def receipts(self, times, epoch=1):
        return [{"ticketId": i + 1, "sourceFrameId": i + 11, "epoch": epoch,
                 "kind": "original", "timeSeconds": t} for i, t in enumerate(times)]

    def test_callback_arrival_order_does_not_define_interval_order(self):
        result = presentation_statistics(self.receipts([1.03, 1., 1.01]), 15_000_000)
        sequence = result["sequence"]
        self.assertEqual([(r["fromTicketId"], r["toTicketId"]) for r in sequence], [(2, 3), (3, 1)])
        self.assertAlmostEqual(sequence[0]["intervalNs"], 10_000_000)
        self.assertEqual(result["longFrameEvents"][0]["toTicketId"], 1)
        self.assertEqual(result["worstIntervals"][0]["toTicketId"], 1)

    def test_quantiles_are_not_inverse_cpu_frame_durations(self):
        result = presentation_statistics(self.receipts([1., 1.01, 1.03, 1.06]), 25_000_000)
        self.assertAlmostEqual(result["p50"], 20_000_000)
        self.assertAlmostEqual(result["p99"], 30_000_000)
        self.assertIsNone(result["p99.9"])
        self.assertEqual(result["p99.9UnavailableReason"], "insufficient-samples")
        self.assertEqual(result["p99.9MinimumSamples"], 10_000)

    def test_p999_requires_ten_thousand_intervals_not_a_single_tail_event(self):
        receipts = self.receipts([i * .01 + 1 for i in range(10_001)])
        self.assertIsNone(presentation_statistics(receipts[:-1], None)["p99.9"])
        self.assertIsNotNone(presentation_statistics(receipts, None)["p99.9"])

    def test_tied_receipts_are_preserved_but_not_promoted(self):
        result = presentation_statistics(self.receipts([1., 1., 1.01]), 20_000_000)
        self.assertEqual(result["samples"], 2)
        self.assertEqual(result["sequence"][0]["intervalNs"], 0)
        self.assertEqual(result["coincidentTimestamps"], 1)
        self.assertIsNone(result["p99.9"])

    def test_stutter_clusters_use_adjacency_not_per_trial_median(self):
        result = presentation_statistics(self.receipts([1., 1.04, 1.09, 1.10, 1.16]), 30_000_000)
        self.assertEqual([r["intervalCount"] for r in result["stutterClusters"]], [2, 1])
        self.assertEqual(round(result["stutterClusters"][0]["durationNs"]), 90_000_000)
        self.assertEqual(result["longFrameThresholdAuthority"], "predeclared-application-intent-not-system-deadline")

    def test_absent_target_does_not_fabricate_stutter_or_deadlines(self):
        result = presentation_statistics(self.receipts([1., 2.]), None)
        self.assertIsNone(result["longFrameEvents"])
        self.assertIsNone(result["stutterClusters"])
        self.assertEqual(result["longFrameUnavailableReason"], "application-threshold-unavailable")

    def test_no_intervals_or_events_are_created_for_empty_or_single_receipt(self):
        for times in ([], [1.]):
            result = presentation_statistics(self.receipts(times), 20_000_000)
            self.assertEqual(result["samples"], 0)
            self.assertIsNone(result["p50"])
            self.assertEqual(result["sequence"], [])

    def test_epoch_mixing_and_invalid_clocks_reject(self):
        mixed = self.receipts([1., 2.]); mixed[-1]["epoch"] = 2
        with self.assertRaises(ValueError): presentation_statistics(mixed, None)
        for bad in (0., -1., math.nan, math.inf, True):
            with self.assertRaises(ValueError): presentation_statistics(self.receipts([bad]), None)
        for bad in (-1, 0, math.inf, True):
            with self.assertRaises(ValueError): presentation_statistics(self.receipts([1.]), bad)


if __name__ == "__main__":
    unittest.main()
