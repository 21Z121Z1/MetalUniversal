"""Physical protocol and identity tests; none are physical measurements."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from frame_trial_contract import (atomic_json, canonical_hash, packaged_identity, parse_json,
                                  sha256, shader_identity, snapshot_identity, trial_order,
                                  validate_pair, verify_loaded)


class FrameTrialContractTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def jar(self, native_change=False):
        build = {"sourceSha": "a" * 40, "treeSha": "b" * 40, "dirty": False, "minecraftVersion": "26.3"}
        native = {"schemaVersion": 1, "build": build, "nativeSha256": hashlib.sha256(b"native").hexdigest()}
        path = self.root / "mod.jar"
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("metallum-build-identity.json", json.dumps(build))
            z.writestr("natives/macos/libmetallum-build-identity.json", json.dumps(native))
            z.writestr("natives/macos/libmetallum.dylib", b"wrong" if native_change else b"native")
        return path

    def snapshot(self):
        path = self.root / "world"
        path.mkdir()
        (path / "level.dat").write_bytes(b"world")
        digest = sha256(path / "level.dat")
        manifest = {"identity": {"snapshotSha256": hashlib.sha256(("level.dat\0" + digest + "\n").encode()).hexdigest()},
                    "files": [{"path": "level.dat", "bytes": 5, "sha256": digest}]}
        (self.root / "world-manifest.json").write_text(json.dumps(manifest))
        return path, manifest

    def variant(self):
        return {"observer": "timing", "backend": "metal3", "producer": "vanilla", "artifact": {"jar": "a" * 64}, "flags": {}}

    def test_protocol_is_fixed_before_any_result(self):
        for protocol, sequence in (("aa", "AA"), ("abba", "ABBA"), ("baab", "BAAB"), ("observer", "ABBA")):
            order = trial_order(protocol, 2)
            self.assertEqual(sequence * 2, "".join(x["variant"] for x in order))
            self.assertEqual(list(range(1, len(order) + 1)), [x["ordinal"] for x in order])
            self.assertEqual([1] * len(sequence) + [2] * len(sequence), [x["block"] for x in order])
        for blocks in (0, 65, True, 1.5):
            with self.assertRaises(ValueError): trial_order("abba", blocks)
        with self.assertRaises(ValueError): trial_order("repeat-until-pass", 4)

    def test_aa_rejects_any_implementation_or_observer_difference(self):
        a = self.variant()
        validate_pair("aa", {"A": a, "B": copy.deepcopy(a)})
        for key, value in (("observer", "off"), ("backend", "metal4"), ("flags", {"reuse": True}), ("artifact", {"jar": "b" * 64})):
            b = copy.deepcopy(a); b[key] = value
            with self.assertRaises(ValueError): validate_pair("aa", {"A": a, "B": b})

    def test_observer_only_changes_observer(self):
        a = self.variant(); a["observer"] = "off"
        b = self.variant()
        validate_pair("observer", {"A": a, "B": b})
        b["observer"] = "diagnostic"
        validate_pair("observer", {"A": a, "B": b})
        b["backend"] = "metal4"
        with self.assertRaises(ValueError): validate_pair("observer", {"A": a, "B": b})

    def test_ab_cannot_change_measurement_or_producer_semantics(self):
        a = self.variant(); b = copy.deepcopy(a); b["backend"] = "metal4"
        validate_pair("abba", {"A": a, "B": b})
        for key, value in (("observer", "off"), ("producer", "iris")):
            b = copy.deepcopy(a); b[key] = value
            with self.assertRaises(ValueError): validate_pair("abba", {"A": a, "B": b})

    def test_packaged_identity_is_bytes_not_caller_sha(self):
        jar = self.jar()
        value = packaged_identity(jar)
        self.assertEqual(sha256(jar), value["javaArtifactSha256"])
        loaded = copy.deepcopy(value)
        verify_loaded(value, loaded, {"identity": {"build": value["build"], "javaArtifactSha256": value["javaArtifactSha256"],
                                                   "nativeSha256": value["packagedNativeSha256"]}})
        loaded["javaArtifactSha256"] = "c" * 64
        with self.assertRaises(ValueError): verify_loaded(value, loaded, None)
        with self.assertRaises(ValueError): packaged_identity(self.jar(native_change=True))

    def test_world_copy_contract_matches_java_digest_and_rejects_mutation(self):
        world, manifest = self.snapshot()
        self.assertEqual(manifest["identity"]["snapshotSha256"], snapshot_identity(world)["snapshotSha256"])
        (world / "level.dat").write_bytes(b"warld")
        with self.assertRaises(ValueError): snapshot_identity(world)

    def test_unlisted_world_file_and_symbolic_link_are_rejected(self):
        world, _ = self.snapshot()
        extra = world / "extra.dat"; extra.write_text("unexpected")
        with self.assertRaises(ValueError): snapshot_identity(world)
        extra.unlink()
        (world / "escape").symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError): snapshot_identity(world)

    def test_missing_content_is_not_synthetic_iris_activation(self):
        with self.assertRaises(ValueError): shader_identity(None, None)
        path = self.root / "shader.zip"
        with zipfile.ZipFile(path, "w") as z: z.writestr("shaders/final.fsh", "void main(){}")
        result = shader_identity(path, sha256(path))
        self.assertEqual(sha256(path) + ".zip", result["stagedName"])
        with self.assertRaises(ValueError): shader_identity(path, "a" * 64)
        with zipfile.ZipFile(path, "w") as z: z.writestr("../shaders/final.fsh", "void main(){}")
        with self.assertRaises(ValueError): shader_identity(path, sha256(path))

    def test_atomic_manifest_keeps_prior_evidence_when_partial_exists(self):
        path = self.root / "manifest.json"
        atomic_json(path, {"complete": False})
        partial = path.with_name(path.name + ".partial"); partial.write_text("interrupted")
        with self.assertRaises(FileExistsError): atomic_json(path, {"complete": True})
        self.assertEqual({"complete": False}, parse_json(path.read_bytes()))

    def test_ambiguous_or_nonfinite_json_is_rejected(self):
        for data in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":Infinity}'):
            with self.assertRaises(ValueError): parse_json(data)
        self.assertEqual(canonical_hash({"a": 1, "b": 2}), canonical_hash({"b": 2, "a": 1}))
        with self.assertRaises(ValueError): canonical_hash({"value": float("nan")})


if __name__ == "__main__": unittest.main()
