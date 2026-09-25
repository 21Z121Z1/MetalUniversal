#!/usr/bin/env python3
"""Verify observed production identity against the exact source/JAR/native under test."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify(identity: dict, mode: str, source: str, jar_hash: str, native_hash: str) -> None:
    if mode not in ('vanilla', 'sodium', 'iris'):
        raise ValueError('unknown renderer mode')
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('source must be an exact commit SHA')
    if any(not re.fullmatch(r'[0-9a-f]{64}', x) for x in (jar_hash, native_hash)):
        raise ValueError('binary identity must be SHA-256')
    mods = identity.get('mods', {})
    checks = {
        'renderer': identity.get('rendererMode') == mode,
        'sodium': ('sodium' in mods) == (mode != 'vanilla'),
        'iris': ('iris' in mods) == (mode == 'iris'),
        'source': identity.get('build', {}).get('sourceSha') == source,
        'clean': identity.get('build', {}).get('dirty') is False,
        'loaded_jar': identity.get('javaArtifactSha256') == jar_hash,
        'packaged_native': identity.get('packagedNativeSha256') == native_hash,
        'loaded_native': identity.get('loadedNativeSha256') == native_hash,
        'observed_jar_path': bool(identity.get('loadedJavaArtifact')),
        'observed_native_path': bool(identity.get('loadedNativeArtifact')),
    }
    if not all(checks.values()):
        raise ValueError('production identity failed: ' + ', '.join(k for k, ok in checks.items() if not ok))


def self_test() -> None:
    import copy
    for mode in ('vanilla', 'sodium', 'iris'):
        mods = {} if mode == 'vanilla' else {'sodium': 'test'}
        if mode == 'iris': mods['iris'] = 'test'
        good = {'rendererMode': mode, 'mods': mods, 'build': {'sourceSha': 'a'*40, 'dirty': False},
                'javaArtifactSha256': 'b'*64, 'packagedNativeSha256': 'c'*64,
                'loadedNativeSha256': 'c'*64, 'loadedJavaArtifact': 'production.jar',
                'loadedNativeArtifact': 'loaded.dylib'}
        verify(good, mode, 'a'*40, 'b'*64, 'c'*64)
        for field in ('rendererMode', 'build', 'javaArtifactSha256', 'packagedNativeSha256',
                      'loadedNativeSha256', 'loadedJavaArtifact', 'loadedNativeArtifact'):
            bad = copy.deepcopy(good); bad.pop(field)
            try: verify(bad, mode, 'a'*40, 'b'*64, 'c'*64)
            except ValueError: pass
            else: raise AssertionError('accepted missing ' + field)
        for mod in ('sodium', 'iris'):
            bad = copy.deepcopy(good)
            if mod in bad['mods']: del bad['mods'][mod]
            else: bad['mods'][mod] = 'unexpected'
            try: verify(bad, mode, 'a'*40, 'b'*64, 'c'*64)
            except ValueError: pass
            else: raise AssertionError('accepted wrong optional mod ' + mod)
        bad = copy.deepcopy(good); bad['build']['dirty'] = True
        try: verify(bad, mode, 'a'*40, 'b'*64, 'c'*64)
        except ValueError: pass
        else: raise AssertionError('accepted dirty build')
    print('loaded artifact identity self-test: PASS')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--identity', type=Path)
    parser.add_argument('--mode', choices=['vanilla', 'sodium', 'iris'])
    parser.add_argument('--source')
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--native', type=Path)
    args = parser.parse_args()
    if args.self_test: self_test(); return
    if any(x is None for x in (args.identity, args.mode, args.source, args.jar)):
        parser.error('--identity --mode --source --jar are required')
    with zipfile.ZipFile(args.jar) as jar:
        native = hashlib.sha256(jar.read('natives/macos/libmetallum.dylib')).hexdigest()
    if args.native is not None and digest(args.native) != native:
        raise SystemExit('build native does not match the packaged native')
    verify(json.loads(args.identity.read_text()), args.mode, args.source, digest(args.jar), native)
    print('loaded production source/JAR/native identity: PASS')

if __name__ == '__main__':
    main()
