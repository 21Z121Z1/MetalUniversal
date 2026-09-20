#!/usr/bin/env python3
"""Focused semantic tests for verify_terrain_generation.py."""
from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_terrain_generation as verifier


class VerifyTerrainGenerationTests(unittest.TestCase):
    def test_active_trace_is_complete_and_active(self) -> None:
        checked = verifier.evaluate(verifier._fixture())
        self.assertTrue(checked["complete"])
        self.assertTrue(checked["guard_active"])

        untracked = verifier._fixture()
        untracked["evidence"]["snapshot"]["untrackedInvalidations"] = "17"
        self.assertTrue(verifier.evaluate(untracked)["complete"])

    def test_fail_open_is_honest_diagnostic_but_not_active(self) -> None:
        checked = verifier.evaluate(verifier._fixture(fail_open=True))
        self.assertTrue(checked["complete"])
        self.assertFalse(checked["guard_active"])

    def test_unobserved_provider_and_disabled_trace_are_accepted_diagnostics(self) -> None:
        unobserved = verifier._fixture()
        unobserved["mixinHooksObserved"] = False
        unobserved["evidence"] = None
        checked = verifier.evaluate(unobserved)
        self.assertTrue(checked["complete"])
        self.assertFalse(checked["guard_active"])

        no_trace = verifier._fixture()
        no_trace["mixinHooksObserved"] = True
        no_trace["evidence"] = {
            "snapshot": no_trace["evidence"]["snapshot"],
            "events": [],
            "droppedEvents": "0",
            "eventCapacity": 0,
        }
        checked = verifier.evaluate(no_trace)
        self.assertTrue(checked["complete"])
        self.assertFalse(checked["guard_active"])

    def test_publication_decision_must_match_versions_and_cancellation(self) -> None:
        stale = verifier._fixture()
        stale["evidence"]["events"][1]["captured"] = copy.deepcopy(
            stale["evidence"]["events"][1]["current"]
        )
        stale["evidence"]["events"][1]["captured"]["geometryRevision"] = "9"
        self.assertFalse(verifier.evaluate(stale)["complete"])

        cancelled = verifier._fixture()
        cancelled["evidence"]["events"][1]["cancelled"] = True
        self.assertFalse(verifier.evaluate(cancelled)["complete"])

        wrong_section = verifier._fixture()
        wrong_section["evidence"]["events"][1]["current"]["sectionId"] = "8"
        self.assertFalse(verifier.evaluate(wrong_section)["complete"])

    def test_complete_trace_reconciles_counters_and_contiguous_sequence(self) -> None:
        fabricated_counter = verifier._fixture()
        fabricated_counter["evidence"]["snapshot"]["allowedPublications"] = "0"
        self.assertFalse(verifier.evaluate(fabricated_counter)["complete"])

        missing_sequence = verifier._fixture()
        missing_sequence["evidence"]["events"][1]["sequence"] = "3"
        self.assertFalse(verifier.evaluate(missing_sequence)["complete"])

    def test_duplicate_sequence_and_dropped_event_reject(self) -> None:
        self.assertFalse(verifier.evaluate(verifier._fixture(duplicate_sequence=True))["complete"])
        dropped = verifier._fixture()
        dropped["evidence"]["droppedEvents"] = "999999"
        checked = verifier.evaluate(dropped)
        self.assertFalse(checked["complete"])
        self.assertEqual(checked["errors"], [])
        self.assertEqual(checked["state"], "valid-lossy")

    def test_nonpassed_run_status_is_diagnostic_not_invalid_shape(self) -> None:
        incomplete = verifier._fixture()
        incomplete["validationStatus"] = "incomplete"
        checked = verifier.evaluate(incomplete)
        self.assertFalse(checked["complete"])
        self.assertEqual(checked["errors"], [])
        self.assertEqual(checked["state"], "valid-incomplete")

        failed = verifier._fixture()
        failed["validationStatus"] = "failed"
        checked = verifier.evaluate(failed)
        self.assertFalse(checked["complete"])
        self.assertEqual(checked["errors"], [])
        self.assertEqual(checked["state"], "valid-failed-trial")

        blocked = verifier._fixture()
        blocked["validationStatus"] = "environment-blocked"
        checked = verifier.evaluate(blocked)
        self.assertFalse(checked["complete"])
        self.assertEqual(checked["errors"], [])
        self.assertEqual(checked["state"], "valid-environment-blocked")


if __name__ == "__main__":
    unittest.main()
