#!/usr/bin/env python3
"""Fail-closed exact-source/JAR/loaded-native and runtime-profile evidence gate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]

def sha256(path):
    with Path(path).open('rb') as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
        return digest.hexdigest()

def require(condition, message):
    if not condition:
        raise ValueError(message)

def verify(identity, jar, source, renderer, root=ROOT, expected_native=None):
    require(re.fullmatch(r'[0-9a-f]{40}', source) is not None, 'exact 40-hex source SHA required')
    require(renderer in ('vanilla', 'sodium', 'iris'), 'unknown renderer profile')
    require(identity.get('rendererMode') == renderer, 'runtime renderer identity mismatch')
    mods = identity.get('mods', {})
    require('metallum' in mods, 'production mod absent')
    require(('sodium' in mods) == (renderer != 'vanilla'), 'unexpected Sodium runtime presence')
    require(('iris' in mods) == (renderer == 'iris'), 'unexpected Iris runtime presence')
    jar_hash = sha256(jar)
    require(identity.get('javaArtifactSha256') == jar_hash, 'observed JAR SHA256 mismatch')
    loaded = Path(identity.get('loadedJavaArtifact', ''))
    require(loaded.is_file() and sha256(loaded) == jar_hash, 'loaded Java code was not the expected packaged JAR')
    with zipfile.ZipFile(jar) as archive:
        build = json.loads(archive.read('metallum-build-identity.json'))
        native_hash = hashlib.sha256(archive.read('natives/macos/libmetallum.dylib')).hexdigest()
        manifest = json.loads(archive.read('natives/macos/libmetallum-build-identity.json'))
    require(build == identity.get('build'), 'observed build identity differs from packaged identity')
    require(build.get('sourceSha') == source and build.get('dirty') is False, 'source SHA or clean-worktree proof mismatch')
    require(build.get('minecraftVersion') == '26.3', 'wrong Minecraft build')
    tree = subprocess.check_output(['git', 'rev-parse', source + '^{tree}'], cwd=root, text=True).strip()
    require(build.get('treeSha') == tree, 'source tree SHA mismatch')
    required_inputs = {'build.gradle', 'src/main/native/MetalFrameGenerationLifecycle.swift',
                       'src/main/native/MetallumInterface.swift', 'src/main/native/MetallumNative.swift'}
    inputs = build.get('nativeSourceInputs', {})
    require(set(inputs) == required_inputs, 'native compiler input inventory incomplete')
    for name, digest in inputs.items():
        source_bytes = subprocess.check_output(['git', 'show', source + ':' + name], cwd=root)
        require(hashlib.sha256(source_bytes).hexdigest() == digest, 'native source input mismatch: ' + name)
    require(manifest.get('nativeSha256') == native_hash and manifest.get('build') == build,
            'native compilation provenance differs from packaged source')
    require(identity.get('packagedNativeSha256') == native_hash, 'packaged native hash mismatch')
    require(identity.get('loadedNativeSha256') == native_hash and bool(identity.get('loadedNativePath')),
            'actual native load is unobserved or has the wrong SHA256')
    if expected_native is not None:
        require(expected_native == native_hash, 'native differs from correctness-approved artifact')
    return {'sourceSha': source, 'treeSha': tree, 'productionJarSha256': jar_hash,
            'nativeDylibSha256': native_hash, 'rendererMode': renderer}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('identity', type=Path)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--source', required=True)
    parser.add_argument('--renderer', required=True, choices=['vanilla', 'sodium', 'iris'])
    parser.add_argument('--native-sha')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        identity = verify(json.loads(args.identity.read_text()), args.jar, args.source, args.renderer,
                          expected_native=args.native_sha)
        result = {'state': 'pass', 'identity': identity}
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        raise SystemExit('Production artifact gate: FAIL: ' + str(error)) from error
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    print(text, end='')

if __name__ == '__main__':
    main()
