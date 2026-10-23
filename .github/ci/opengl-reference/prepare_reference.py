#!/usr/bin/env python3
"""Stage a pinned public pack; never commit or upload shader-pack binaries."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
import urllib.request

PACK_URL = 'https://cdn.modrinth.com/data/Q1vvjJYV/versions/Y68yiql9/BSL_v10.1.8.zip'
PACK_SHA256 = '36b0a50ff7918bf10e9422c401d93779f9d0088a26acea975b435243f96930b5'
PACK_NAME = 'BSL_v10.1.8.zip'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--renderer', required=True, choices=('vanilla', 'sodium', 'iris'))
    parser.add_argument('--instance', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--world-snapshot', type=Path,
                        help='Verified immutable WorldSnapshot directory; only an isolated copy is opened')
    parser.add_argument('--width', type=int)
    parser.add_argument('--height', type=int)
    parser.add_argument('--distance', type=int)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(repository / 'scripts/agent'))
    from frame_trial_contract import snapshot_identity
    dimensions = (args.width, args.height, args.distance)
    if args.world_snapshot:
        if any(value is None for value in dimensions):
            parser.error('Saved-world references require explicit --width, --height and --distance')
        snapshot = args.world_snapshot.resolve(strict=True)
        if args.instance.resolve().is_relative_to(snapshot):
            parser.error('The isolated instance must be outside the original snapshot')
        identity = snapshot_identity(args.world_snapshot)
    else:
        if any(value is not None for value in dimensions):
            parser.error('Custom dimensions require --world-snapshot; hosted smoke settings are fixed')
        args.width, args.height, args.distance = 1280, 720, 12
    if not (64 <= args.width <= 8192 and 64 <= args.height <= 8192 and 2 <= args.distance <= 32):
        parser.error('Invalid reference dimensions/distance')
    # Every run owns a fresh instance, so failed reference evidence is never overwritten.
    args.instance.mkdir(parents=True, exist_ok=False)
    args.output.mkdir(parents=True, exist_ok=True)
    versions = dict(line.split('=', 1) for line in (repository / 'gradle.properties').read_text().splitlines()
                    if '=' in line and not line.lstrip().startswith('#'))
    inputs = {'scenario': 'hosted-opengl-reference-v1', 'renderer': args.renderer,
              'width': args.width, 'height': args.height, 'renderDistance': args.distance,
              'loaderVersion': versions['loader_version'].strip(),
              'claim': 'Explicit hosted smoke profile, not physical 32-distance quality/performance acceptance',
              'shaderPack': None}
    if args.world_snapshot:
        staged = args.instance / 'saves/reference-snapshot'
        staged.parent.mkdir()
        shutil.copytree(snapshot, staged)
        shutil.copyfile(snapshot.with_name(snapshot.name + '-manifest.json'),
                        staged.with_name(staged.name + '-manifest.json'))
        if snapshot_identity(staged) != identity or snapshot_identity(snapshot) != identity:
            raise ValueError('World snapshot changed while staging its isolated copy')
        inputs.update(scenario='saved-world-opengl-reference-v1',
                      claim='Saved-world OpenGL reference; temporal/preprocessing parity and performance require separate gates',
                      quality={'requestedPreset': 'FABULOUS', 'simulationDistance': 12,
                               'vsync': False, 'fpsLimitOption': 260},
                      worldSnapshot={**identity, 'saveDirectory': staged.name, 'source': str(snapshot)})
    if args.renderer == 'iris':
        request = urllib.request.Request(PACK_URL, headers={'User-Agent': 'MetalUniversal-OpenGL-reference/1.0'})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read(8 * 1024 * 1024)
        if hashlib.sha256(data).hexdigest() != PACK_SHA256:
            raise ValueError('Pinned BSL download digest mismatch')
        packs = args.instance / 'shaderpacks'
        packs.mkdir()
        (packs / PACK_NAME).write_bytes(data)
        config = args.instance / 'config'
        config.mkdir()
        (config / 'iris.properties').write_text('enableShaders=true\nshaderPack=' + PACK_NAME + '\n')
        inputs['shaderPack'] = {'url': PACK_URL, 'name': PACK_NAME, 'sha256': PACK_SHA256}
    (args.output / 'inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')


if __name__ == '__main__':
    main()
