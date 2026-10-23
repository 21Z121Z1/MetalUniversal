import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from verify_reference import verify


class ReferenceOracleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.driver = self.root / 'driver.java'
        self.driver.write_bytes(b'independent driver')
        self.jar = self.root / 'harness.jar'
        self.jar.write_bytes(b'packaged harness')
        self.raw = bytes([255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 0, 255])
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
        png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 2, 2, 8, 6, 0, 0, 0))
        png += chunk(b'IDAT', zlib.compress(b'\0' + self.raw[8:] + b'\0' + self.raw[:8])) + chunk(b'IEND', b'')
        (self.root / 'frame.png').write_bytes(png)
        (self.root / 'frame.rgba8').write_bytes(self.raw)
        self.inputs = {'scenario': 'hosted-opengl-reference-v1', 'renderer': 'iris', 'width': 2, 'height': 2,
                       'renderDistance': 12, 'shaderPack': {'name': 'pack.zip', 'sha256': 'b' * 64}}
        self.report = {'schemaVersion': 1, 'status': 'passed', 'scenario': self.inputs['scenario'], 'sourceSha': 'a' * 40,
                       'buildIdentity': {'sourceSha': 'a' * 40, 'driverSha256': hashlib.sha256(self.driver.read_bytes()).hexdigest()},
                       'harnessJarSha256': hashlib.sha256(self.jar.read_bytes()).hexdigest(), 'renderer': 'iris',
                       'mods': {'minecraft': '26.3', 'sodium': 'pinned', 'iris': 'pinned'}, 'serverFrozen': True,
                       'expectedChunks': 9, 'receivedChunks': 9,
                       'capture': {'backend': 'OpenGL', 'glRenderer': 'reference driver', 'glVersion': '4.1', 'glError': 0,
                                   'controlExact': True, 'clientFrozen': True, 'playerName': 'ReferencePlayer',
                                   'playerUuid': '8f16930a-42ad-4f9b-9d59-02698f26b145', 'renderDistance': 12, 'loadedChunks': 9,
                                   'observedCameraX': 160.5, 'observedCameraY': 140, 'observedCameraZ': 160.5,
                                   'width': 2, 'height': 2, 'format': 'RGBA8_UNORM', 'rawOrigin': 'lower-left', 'byteCount': 16,
                                   'rawSha256': hashlib.sha256(self.raw).hexdigest(), 'pngSha256': hashlib.sha256(png).hexdigest(),
                                   'irisPipeline': 'net.irisshaders.iris.pipeline.IrisRenderingPipeline',
                                   'shaderPack': 'pack.zip', 'shaderPackSha256': 'b' * 64}}

    def check(self, report=None):
        (self.root / 'inputs.json').write_text(json.dumps(self.inputs))
        (self.root / 'reference.json').write_text(json.dumps(report or self.report))
        return verify(self.root, 'a' * 40, self.driver, self.jar)

    def test_complete_reference_passes(self):
        self.check()

    def test_rejects_substitutions_and_incomplete_receipts(self):
        mutations = [
            ('status', 'running'), ('sourceSha', 'c' * 40), ('harnessJarSha256', 'c' * 64),
            ('mods.metallum', '1.0'), ('capture.backend', 'Metal'), ('capture.controlExact', False),
            ('capture.glError', 1282), ('capture.clientFrozen', False), ('serverFrozen', False), ('receivedChunks', 8),
            ('capture.observedCameraX', 159.0), ('capture.renderDistance', 8), ('capture.playerName', 'Random'),
            ('capture.irisPipeline', 'FixedFunctionWorldRenderingPipeline'), ('capture.shaderPackSha256', 'c' * 64),
            ('capture.byteCount', 15), ('capture.rawSha256', 'c' * 64), ('capture.pngSha256', 'c' * 64),
            ('buildIdentity.driverSha256', 'c' * 64),
        ]
        for key, value in mutations:
            with self.subTest(key=key):
                report = copy.deepcopy(self.report)
                target = report
                parts = key.split('.')
                for part in parts[:-1]: target = target[part]
                target[parts[-1]] = value
                with self.assertRaises(ValueError): self.check(report)

    def test_rejects_self_consistent_uniform_pixels(self):
        raw = bytes([0, 0, 0, 255] * 4)
        (self.root / 'frame.rgba8').write_bytes(raw)
        self.report['capture']['rawSha256'] = hashlib.sha256(raw).hexdigest()
        with self.assertRaises(ValueError): self.check()

    def test_rejects_corrupt_png_even_with_matching_hash(self):
        path = self.root / 'frame.png'
        png = path.read_bytes()[:-12]
        path.write_bytes(png)
        self.report['capture']['pngSha256'] = hashlib.sha256(png).hexdigest()
        with self.assertRaises(ValueError): self.check()

    def test_rejects_png_pixel_substitution(self):
        raw = bytes([0, 0, 255, 255, 255, 255, 0, 255] * 2)
        (self.root / 'frame.rgba8').write_bytes(raw)
        self.report['capture']['rawSha256'] = hashlib.sha256(raw).hexdigest()
        with self.assertRaises(ValueError): self.check()

    def test_vanilla_without_optional_mods_passes(self):
        self.inputs.update(renderer='vanilla', shaderPack=None)
        self.report.update(renderer='vanilla', mods={'minecraft': '26.3'})
        for key in ('irisPipeline', 'shaderPack', 'shaderPackSha256'): self.report['capture'].pop(key)
        self.check()


if __name__ == '__main__': unittest.main()
