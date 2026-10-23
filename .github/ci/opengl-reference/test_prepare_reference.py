"""Exercise staging with an actual immutable snapshot manifest, without Minecraft/network."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class SavedWorldStagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.snapshot = self.root / 'initial-world'
        self.snapshot.mkdir()
        (self.snapshot / 'level.dat').write_bytes(b'original level data')
        (self.snapshot / 'data').mkdir()
        (self.snapshot / 'data/state.dat').write_bytes(b'original saved state')
        rows = []
        digest = hashlib.sha256()
        for path in sorted(p for p in self.snapshot.rglob('*') if p.is_file()):
            name = path.relative_to(self.snapshot).as_posix()
            sha = hashlib.sha256(path.read_bytes()).hexdigest()
            rows.append({'path': name, 'bytes': path.stat().st_size, 'sha256': sha})
            digest.update((name + '\0' + sha + '\n').encode())
        self.sha = digest.hexdigest()
        self.manifest = self.snapshot.with_name('initial-world-manifest.json')
        self.manifest.write_text(json.dumps({'identity': {'seed': 1, 'minecraftVersion': '26.3',
                                                         'snapshotSha256': self.sha}, 'files': rows}))
        self.instance = self.root / 'instance'
        self.output = self.root / 'output'

    def prepare(self, dimensions=True):
        command = [sys.executable, str(Path(__file__).with_name('prepare_reference.py')),
                   '--renderer', 'vanilla', '--instance', str(self.instance), '--output', str(self.output),
                   '--world-snapshot', str(self.snapshot)]
        if dimensions:
            command += ['--width', '3024', '--height', '1964', '--distance', '32']
        return subprocess.run(command, capture_output=True, text=True, timeout=10)

    def test_copies_verified_snapshot_and_explicit_native_profile(self):
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        inputs = json.loads((self.output / 'inputs.json').read_text())
        self.assertEqual(inputs['scenario'], 'saved-world-opengl-reference-v1')
        self.assertEqual((inputs['width'], inputs['height'], inputs['renderDistance']), (3024, 1964, 32))
        self.assertEqual(inputs['worldSnapshot']['snapshotSha256'], self.sha)
        self.assertEqual(inputs['worldSnapshot']['fileCount'], 2)
        staged = self.instance / 'saves/reference-snapshot'
        self.assertEqual((staged / 'level.dat').read_bytes(), b'original level data')
        self.assertEqual((staged / 'data/state.dat').read_bytes(), b'original saved state')
        self.assertEqual(staged.with_name('reference-snapshot-manifest.json').read_bytes(), self.manifest.read_bytes())
        (staged / 'level.dat').write_bytes(b'game saves must remain isolated')
        self.assertEqual((self.snapshot / 'level.dat').read_bytes(), b'original level data')

    def test_saved_world_requires_explicit_dimensions_and_distance(self):
        result = self.prepare(dimensions=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.output / 'inputs.json').exists())

    def test_rejects_changed_or_symlinked_snapshot(self):
        for mode in ('changed', 'symlink'):
            with self.subTest(mode=mode):
                path = self.snapshot / 'data/state.dat'
                path.unlink()
                if mode == 'changed':
                    path.write_bytes(b'changed since snapshot')
                else:
                    path.symlink_to(self.snapshot / 'level.dat')
                result = self.prepare()
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.output / 'inputs.json').exists())

    def test_refuses_existing_instance_without_overwriting_it(self):
        self.instance.mkdir()
        marker = self.instance / 'owned-by-another-run'
        marker.write_bytes(b'preserve')
        self.assertNotEqual(self.prepare().returncode, 0)
        self.assertEqual(marker.read_bytes(), b'preserve')


if __name__ == '__main__':
    unittest.main()
