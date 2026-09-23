import json
from pathlib import Path
import unittest
from fixed_cadence_energy import integrate, compare, PROFILE


class FrameEnergyTest(unittest.TestCase):
    def test_registered_profile_and_executable_thresholds_agree(self):
        path = Path(__file__).resolve().parents[2] / "docs/agent/unified-evaluation-acceptance.json"
        contract = json.loads(path.read_text())["efficiency_profiles"][PROFILE["id"]]
        names = {"version": "version", "minimum_paired_blocks": "minimumPairedBlocks",
                 "minimum_positive_block_fraction": "minimumPositiveFraction",
                 "minimum_target_fps_fraction": "minimumTargetFraction",
                 "source_fps_relative_regression_max": "sourceFpsRegressionMax",
                 "source_p99_relative_regression_max": "sourceP99RegressionMax",
                 "maximum_clock_uncertainty_ns": "maximumClockUncertaintyNs",
                 "maximum_sample_interval_ns": "maximumSampleIntervalNs",
                 "minimum_warmup_ns": "minimumWarmupNs", "minimum_sample_ns": "minimumSampleNs"}
        for registered, executable in names.items():
            self.assertEqual(contract[registered], PROFILE[executable], registered)

    def trace(self):
        return {"schemaVersion": 1, "trialId": "trial", "artifact": {"sha": "exact"},
                "sourceWindow": {"clock": "System.nanoTime", "startNs": 10, "endNs": 1_000_000_010},
                "source": {"id": "test-instrument", "version": "1", "method": "fixture-only",
                           "domain": "soc", "authority": "platform-reported", "unit": "W"},
                "sampleKind": "interval-mean", "clockMapping": {"method": "fixture", "evidence": "fixture", "maximumErrorNs": 0},
                "samples": [{"startNs": 10, "endNs": 500_000_010, "meanWatts": 2},
                            {"startNs": 500_000_010, "endNs": 1_000_000_010, "meanWatts": 4}]}

    def integrate(self, trace):
        return integrate(trace, "trial", {"sha": "exact"}, self.trace()["sourceWindow"])

    def test_exact_interval_integration_and_domain_are_preserved(self):
        value = self.integrate(self.trace())
        self.assertEqual(3, value["energyJoules"])
        self.assertEqual(3, value["meanPowerWatts"])
        self.assertFalse(value["wholeSystemClaim"])

    def test_gaps_overlap_negative_nonfinite_and_incomplete_samples_fail(self):
        for field, value in (("startNs", 11), ("endNs", 500_000_011), ("meanWatts", -1), ("meanWatts", float("nan"))):
            trace = self.trace(); trace["samples"][0][field] = value
            with self.assertRaises(ValueError): self.integrate(trace)
        trace = self.trace(); trace["samples"].pop()
        with self.assertRaises(ValueError): self.integrate(trace)

    def test_trial_identity_clock_and_unit_are_not_guessed(self):
        for key, value in (("trialId", "other"), ("artifact", {}), ("sourceWindow", {})):
            trace = self.trace(); trace[key] = value
            with self.assertRaises(ValueError): self.integrate(trace)
        for key, value in (("unit", "mW"), ("authority", "inferred-from-utilization"), ("domain", "unknown")):
            trace = self.trace(); trace["source"][key] = value
            with self.assertRaises(ValueError): self.integrate(trace)
        trace = self.trace(); trace["clockMapping"]["maximumErrorNs"] = 1_000_001
        with self.assertRaises(ValueError): self.integrate(trace)

    def campaign(self):
        plan = {"protocol": "abba", "sampleNs": 120_000_000_000, "warmupNs": 30_000_000_000, "targetFps": 60}
        trials = []
        for block in range(1, 5):
            for variant in "ABBA":
                energy = self.integrate(self.trace())
                energy["energyJoules"] = 10 if variant == "A" else 9
                energy["meanPowerWatts"] = energy["energyJoules"] / 120
                energy["sourceWindow"]["endNs"] = energy["sourceWindow"]["startNs"] + plan["sampleNs"]
                trials.append({"block": block, "variant": variant, "observation": {"energy": energy,
                    "sourceSampleWindow": {**energy["sourceWindow"], "fps": 60, "intervalP99UpperBoundMs": 17}}})
        return plan, trials

    def test_fixed_cadence_does_not_require_faster_fps_or_claim_product_acceptance(self):
        plan, trials = self.campaign()
        result = compare(plan, trials, True)
        self.assertEqual("energy-direction-passed", result["status"])
        self.assertEqual(10, result["pairedMedianImprovementPercent"])
        self.assertFalse(result["productPromotable"])
        self.assertEqual("unavailable", compare(plan, trials, False)["status"])

    def test_lower_cadence_or_missing_power_never_passes(self):
        plan, trials = self.campaign()
        trials[1]["observation"]["sourceSampleWindow"]["fps"] = 40
        self.assertEqual("rejected-regression", compare(plan, trials, True)["status"])
        trials[1]["observation"]["energy"] = {"available": False}
        self.assertEqual("unavailable", compare(plan, trials, True)["status"])

    def test_mixed_sensor_or_domain_is_rejected(self):
        plan, trials = self.campaign()
        trials[1]["observation"]["energy"]["source"]["domain"] = "wall-system"
        with self.assertRaises(ValueError): compare(plan, trials, True)


if __name__ == "__main__": unittest.main()
