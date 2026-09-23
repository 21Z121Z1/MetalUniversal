#!/usr/bin/env python3
"""Negative tests for the evidence verifier, not physical runtime evidence."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from check_production_identity import verify, sha256

class IdentityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        def git(*args):
            return subprocess.check_output(['git', *args], cwd=self.root, text=True, stderr=subprocess.DEVNULL).strip()
        git('init');git('config','user.name','Fixture');git('config','user.email','fixture@example.invalid')
        inputs={}
        for name in ('build.gradle','src/main/native/MetalFrameGenerationLifecycle.swift',
                     'src/main/native/MetallumInterface.swift','src/main/native/MetallumNative.swift'):
            p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(name)
            inputs[name]=sha256(p)
        git('add','.');git('commit','-m','fixture')
        self.source=git('rev-parse','HEAD');tree=git('rev-parse','HEAD^{tree}')
        build=dict(sourceSha=self.source,treeSha=tree,dirty=False,minecraftVersion='26.3',nativeSourceInputs=inputs)
        native=b'synthetic-test-bytes-not-a-dylib';digest=hashlib.sha256(native).hexdigest()
        self.jar=self.root/'fixture.jar'
        with zipfile.ZipFile(self.jar,'w') as z:
            z.writestr('metallum-build-identity.json',json.dumps(build))
            z.writestr('natives/macos/libmetallum.dylib',native)
            z.writestr('natives/macos/libmetallum-build-identity.json',json.dumps({'build':build,'nativeSha256':digest}))
        self.identity=dict(rendererMode='sodium',mods={'metallum':'test','sodium':'test'},
                           build=build,loadedJavaArtifact=str(self.jar),javaArtifactSha256=sha256(self.jar),
                           packagedNativeSha256=digest,loadedNativeSha256=digest,loadedNativePath='observed-at-runtime')
    def check(self):
        return verify(self.identity,self.jar,self.source,'sodium',root=self.root)
    def test_matching_synthetic_fixture(self): self.assertEqual(self.check()['rendererMode'],'sodium')
    def test_iris_in_sodium_lane(self):
        self.identity['mods']['iris']='test'
        with self.assertRaises(ValueError): self.check()
    def test_missing_sodium(self):
        del self.identity['mods']['sodium']
        with self.assertRaises(ValueError): self.check()
    def test_unobserved_native(self):
        del self.identity['loadedNativeSha256']
        with self.assertRaises(ValueError): self.check()
    def test_wrong_loaded_native(self):
        self.identity['loadedNativeSha256']='0'*64
        with self.assertRaises(ValueError): self.check()
    def test_wrong_jar(self):
        self.identity['javaArtifactSha256']='0'*64
        with self.assertRaises(ValueError): self.check()
    def test_wrong_source(self):
        self.source='0'*40
        with self.assertRaises(ValueError): self.check()
    def test_dirty(self):
        self.identity['build']['dirty']=True
        with self.assertRaises(ValueError): self.check()
    def test_dev_classes(self):
        self.identity['loadedJavaArtifact']=str(self.root)
        with self.assertRaises(ValueError): self.check()

if __name__=='__main__': unittest.main()
