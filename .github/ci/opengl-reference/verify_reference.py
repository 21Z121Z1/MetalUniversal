#!/usr/bin/env python3
"""Reject missing, substituted or incomplete independent OpenGL receipts."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zlib


def png_rgba(png, width, height):
    require(png[:8] == b'\x89PNG\r\n\x1a\n', 'Missing PNG signature')
    offset, image_data, header, ended = 8, bytearray(), None, False
    while offset < len(png):
        require(offset + 12 <= len(png), 'Truncated PNG chunk')
        length = struct.unpack_from('>I', png, offset)[0]
        kind = png[offset + 4:offset + 8]
        end = offset + 12 + length
        require(end <= len(png), 'Truncated PNG payload')
        payload = png[offset + 8:end - 4]
        require(zlib.crc32(kind + payload) == struct.unpack_from('>I', png, end - 4)[0], 'PNG CRC mismatch')
        if header is None:
            require(kind == b'IHDR' and length == 13, 'Missing PNG header')
            header = struct.unpack('>IIBBBBB', payload)
            require(header[:3] == (width, height, 8) and header[3] in (2, 6) and header[4:] == (0, 0, 0), 'Unsupported PNG dimensions/encoding')
        elif kind == b'IDAT': image_data.extend(payload)
        elif kind == b'IEND':
            require(length == 0 and end == len(png), 'Invalid PNG ending')
            ended = True
            break
        offset = end
    require(ended and image_data, 'Incomplete PNG image')
    bpp = 4 if header[3] == 6 else 3
    stride = width * bpp
    decoded = zlib.decompress(image_data)
    require(len(decoded) == height * (stride + 1), 'Wrong PNG data length')
    previous, rgba = bytearray(stride), bytearray()
    for y in range(height):
        start = y * (stride + 1)
        filter_kind = decoded[start]
        require(filter_kind <= 4, 'Invalid PNG row filter')
        row = bytearray(decoded[start + 1:start + 1 + stride])
        for x in range(stride):
            left = row[x - bpp] if x >= bpp else 0
            up = previous[x]
            diagonal = previous[x - bpp] if x >= bpp else 0
            if filter_kind == 1: prediction = left
            elif filter_kind == 2: prediction = up
            elif filter_kind == 3: prediction = (left + up) // 2
            elif filter_kind == 4:
                estimate = left + up - diagonal
                distances = (abs(estimate - left), abs(estimate - up), abs(estimate - diagonal))
                prediction = (left, up, diagonal)[distances.index(min(distances))]
            else: prediction = 0
            row[x] = (row[x] + prediction) & 255
        previous = row
        if bpp == 4: rgba.extend(row)
        else:
            for x in range(0, stride, 3): rgba.extend(row[x:x + 3] + b'\xff')
    return bytes(rgba)


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def verify(root, source_sha, driver, harness):
    require(not (root / 'timeout.json').exists(), 'Reference exceeded its wall-clock budget')
    execution = json.loads((root / 'execution.json').read_text())
    require(type(execution.get('exitCode')) is int and execution['exitCode'] == 0
            and execution.get('sourceSha') == source_sha,
            'Reference process did not exit successfully for this source')
    report = json.loads((root / 'reference.json').read_text())
    inputs = json.loads((root / 'inputs.json').read_text())
    require(report.get('schemaVersion') == 1 and report.get('status') == 'passed', 'Reference did not complete')
    scenario = inputs.get('scenario')
    require(report.get('scenario') == scenario and scenario in
            ('hosted-opengl-reference-v1', 'saved-world-opengl-reference-v1'), 'Wrong scenario')
    if scenario == 'saved-world-opengl-reference-v1':
        expected, observed = inputs.get('worldSnapshot'), report.get('worldSnapshot')
        require(isinstance(expected, dict) and isinstance(observed, dict), 'Missing saved-world identity')
        require(expected.get('saveDirectory') == 'reference-snapshot', 'Wrong isolated save directory')
        for field in ('snapshotSha256', 'manifestSha256'):
            digest = expected.get(field)
            require(isinstance(digest, str) and len(digest) == 64
                    and all(c in '0123456789abcdef' for c in digest)
                    and observed.get(field) == digest, 'World snapshot digest mismatch: ' + field)
        for field in ('fileCount', 'bytes'):
            require(type(expected.get(field)) is int and expected[field] > 0
                    and type(observed.get(field)) is int and observed[field] == expected[field],
                    'World snapshot content mismatch: ' + field)
        requested = {'requestedPreset': 'FABULOUS', 'simulationDistance': 12,
                     'vsync': False, 'fpsLimitOption': 260}
        require(inputs.get('quality') == report.get('requestedQuality') == requested,
                'Missing or substituted saved-world quality profile')
        quality = report.get('capture', {}).get('quality')
        before, preset = report.get('qualityBeforeSettling'), report.get('qualityAfterPreset')
        require(isinstance(quality, dict) and isinstance(before, dict) and isinstance(preset, dict),
                'Missing actual saved-world quality observations')
        fields = {'renderDistance', 'effectiveRenderDistance', 'simulationDistance', 'graphicsPreset',
                  'ambientOcclusion', 'clouds', 'cloudRange', 'particles', 'mipmapLevels',
                  'entityDistanceScaling', 'entityShadows', 'biomeBlendRadius', 'improvedTransparency',
                  'textureFiltering', 'maxAnisotropyBit', 'cutoutLeaves', 'weatherRadius',
                  'vsync', 'fpsLimitOption', 'fov', 'gamma', 'anisotropyHasKnownIssues'}
        require(fields <= quality.keys() and fields <= preset.keys(), 'Incomplete actual quality tuple')
        # Minecraft 26.3 GraphicsPreset.apply; pinned Iris's MixinDisableFabulousGraphics
        # owns improvedTransparency=false/CUSTOM while its shader pipeline is enabled.
        iris = inputs.get('renderer') == 'iris'
        for observed in (preset, quality):
            require(type(observed.get('anisotropyHasKnownIssues')) is bool, 'Missing actual filtering capability')
            expected_options = {'ambientOcclusion': True, 'clouds': 'FANCY', 'cloudRange': 128,
                                'particles': 'ALL', 'mipmapLevels': 4, 'entityDistanceScaling': 1.25,
                                'entityShadows': True, 'biomeBlendRadius': 2, 'maxAnisotropyBit': 2,
                                'cutoutLeaves': True, 'weatherRadius': 10, 'improvedTransparency': not iris,
                                'textureFiltering': 'RGSS' if observed['anisotropyHasKnownIssues'] else 'ANISOTROPIC'}
            for key, value in expected_options.items():
                require(observed.get(key) == value, 'Requested Fabulous option changed: ' + key)
        require(preset.get('graphicsPreset') == ('CUSTOM' if iris else 'FABULOUS'),
                'Unexpected upstream preset ownership')
        require(before == quality, 'Quality changed during settling/capture')
        require(quality.get('renderDistance') == quality.get('effectiveRenderDistance') == inputs['renderDistance']
                and quality.get('simulationDistance') == 12 and quality.get('vsync') is False
                and quality.get('fpsLimitOption') == 260, 'Actual saved-world profile differs')
        dimensions = report.get('capture', {}).get('dimensions')
        require(isinstance(dimensions, dict), 'Missing physical/surface/render dimensions')
        for axis, expected_size in (('Width', inputs['width']), ('Height', inputs['height'])):
            for prefix in ('nativeWindowPixel', 'surface', 'framebuffer', 'render'):
                value = dimensions.get(prefix + axis)
                require(type(value) is int and value == expected_size,
                        'Physical/surface/render size mismatch: ' + prefix + axis)
    else:
        require('worldSnapshot' not in inputs and 'worldSnapshot' not in report, 'Hosted world was substituted')
    require(report.get('sourceSha') == source_sha == report.get('buildIdentity', {}).get('sourceSha'), 'Wrong source SHA')
    require(report['buildIdentity'].get('driverSha256') == hashlib.sha256(driver.read_bytes()).hexdigest(), 'Wrong loaded driver')
    require(report.get('harnessJarSha256') == hashlib.sha256(harness.read_bytes()).hexdigest(), 'Wrong production harness JAR')
    lane = inputs['renderer']
    require(execution.get('renderer') == lane, 'Wrong execution renderer')
    require(lane in ('vanilla', 'sodium', 'iris') and report.get('renderer') == lane, 'Wrong renderer lane')
    mods = report['mods']
    require('metallum' not in mods, 'Reference loaded MetalUniversal')
    require(bool(inputs.get('loaderVersion')) and mods.get('fabricloader') == inputs['loaderVersion'], 'Wrong Fabric Loader runtime')
    require(('sodium' in mods) == (lane != 'vanilla') and ('iris' in mods) == (lane == 'iris'), 'Wrong optional mods')
    require(mods.get('minecraft') == '26.3', 'Wrong Minecraft version')
    capture = report['capture']
    require(capture.get('backend') == 'OpenGL', 'Backend fallback is not a reference pass')
    require(bool(capture.get('glRenderer')) and bool(capture.get('glVersion')), 'Missing actual GL driver')
    require(capture.get('glError') == 0 and capture.get('controlExact') is True, 'OpenGL control/error failure')
    require(report.get('serverFrozen') is True and capture.get('clientFrozen') is True, 'Unfrozen reference scene')
    require(type(report.get('settledTicks')) is int and report['settledTicks'] == 40, 'Incomplete reference settling period')
    require(report.get('expectedChunks', 0) > 0 and report.get('receivedChunks') == report['expectedChunks'], 'Incomplete server sending view')
    require(capture.get('playerName') == 'ReferencePlayer' and capture.get('playerUuid') == '8f16930a-42ad-4f9b-9d59-02698f26b145', 'Wrong player identity')
    require(capture.get('renderDistance') == inputs['renderDistance'] and capture.get('loadedChunks', 0) > 0, 'World/distance mismatch')
    for axis, position in (('X', 160.5), ('Y', 140), ('Z', 160.5)):
        require(abs(capture['observedCamera' + axis] - position) < 0.001, 'Wrong camera ' + axis)
    require((capture.get('width'), capture.get('height')) == (inputs['width'], inputs['height']), 'Wrong target dimensions')
    require(capture.get('format') == 'RGBA8_UNORM' and capture.get('rawOrigin') == 'lower-left', 'Wrong readback format/origin')
    raw = (root / 'frame.rgba8').read_bytes()
    require(len(raw) == capture.get('byteCount') == inputs['width'] * inputs['height'] * 4, 'Incomplete GPU readback')
    require(hashlib.sha256(raw).hexdigest() == capture.get('rawSha256'), 'Raw image digest mismatch')
    colors = set(zip(raw[0::4], raw[1::4], raw[2::4]))
    require(len(colors) > 1 and any(color != (0, 0, 0) for color in colors), 'Empty/uniform world capture')
    png = (root / 'frame.png').read_bytes()
    require(len(png) > 24 and png[:8] == b'\x89PNG\r\n\x1a\n' and png[12:16] == b'IHDR', 'Missing/malformed PNG')
    require(struct.unpack('>II', png[16:24]) == (inputs['width'], inputs['height']), 'Wrong PNG dimensions')
    require(hashlib.sha256(png).hexdigest() == capture.get('pngSha256'), 'PNG digest mismatch')
    row_bytes = inputs['width'] * 4
    top_down = b''.join(raw[y * row_bytes:(y + 1) * row_bytes] for y in reversed(range(inputs['height'])))
    require(png_rgba(png, inputs['width'], inputs['height']) == top_down, 'PNG pixels differ from the GPU readback')
    if lane == 'iris':
        require(report.get('irisShadersAtWorldCreation') is False, 'Wrong Iris scene preparation')
        require(report.get('receivedChunksAfterIrisActivation') == report['expectedChunks'], 'Incomplete view after Iris activation')
        require(capture.get('irisPipeline') == 'net.irisshaders.iris.pipeline.IrisRenderingPipeline', 'Iris fell back or is inactive')
        require(capture.get('shaderPack') == inputs['shaderPack']['name'] and capture.get('shaderPackSha256') == inputs['shaderPack']['sha256'], 'Wrong active shader pack')
    else:
        require('irisPipeline' not in capture and inputs['shaderPack'] is None, 'Unexpected pack state')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--harness', required=True, type=Path)
    args = parser.parse_args()
    driver = Path(__file__).parent / 'src/main/java/com/metallum/reference/OpenGlReferenceGameTest.java'
    report = verify(args.root, args.source_sha, driver, args.harness)
    print('Verified independent OpenGL reference: ' + report['renderer'])


if __name__ == '__main__':
    main()
