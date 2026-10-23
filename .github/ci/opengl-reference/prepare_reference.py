#!/usr/bin/env python3
"""Stage a pinned public pack; never commit or upload shader-pack binaries."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

PACK_URL = 'https://cdn.modrinth.com/data/Q1vvjJYV/versions/Y68yiql9/BSL_v10.1.8.zip'
PACK_SHA256 = '36b0a50ff7918bf10e9422c401d93779f9d0088a26acea975b435243f96930b5'
PACK_NAME = 'BSL_v10.1.8.zip'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--renderer', required=True, choices=('vanilla', 'sodium', 'iris'))
    parser.add_argument('--instance', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    # Every run owns a fresh instance, so failed reference evidence is never overwritten.
    args.instance.mkdir(parents=True, exist_ok=False)
    args.output.mkdir(parents=True, exist_ok=True)
    repository = Path(__file__).resolve().parents[3]
    versions = dict(line.split('=', 1) for line in (repository / 'gradle.properties').read_text().splitlines()
                    if '=' in line and not line.lstrip().startswith('#'))
    inputs = {'scenario': 'hosted-opengl-reference-v1', 'renderer': args.renderer,
              'width': 1280, 'height': 720, 'renderDistance': 12,
              'loaderVersion': versions['loader_version'].strip(),
              'claim': 'Explicit hosted smoke profile, not physical 32-distance quality/performance acceptance',
              'shaderPack': None}
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
