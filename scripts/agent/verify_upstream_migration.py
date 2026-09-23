#!/usr/bin/env python3
"""Regression contracts for the 26.3 upstream transplant (no GPU claim)."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
def read(path): return (ROOT / path).read_text()
def require(value, reason):
    if not value: raise SystemExit('Upstream migration contract failed: ' + reason)
def props(path):
    return dict(line.split('=', 1) for line in read(path).splitlines() if '=' in line and not line.startswith('#'))
root, client = props('gradle.properties'), props('.github/ci/minecraft-e2e/gradle.properties')
for key in ('minecraft_version', 'loader_version', 'loom_version', 'sodium_version', 'iris_version'):
    require(root[key] == client[key], 'client and production pin differ: ' + key)
require(root['minecraft_version'] == '26.3', 'wrong Minecraft release')
metadata = json.loads(read('src/main/resources/fabric.mod.json'))
require(not {'sodium', 'iris'} & set(metadata['depends']), 'optional mod became a required dependency')
require('com.metallum.client.validation.MetalValidationClient' in metadata['entrypoints']['client'], 'missing opt-in validation entrypoint')
plugin = read('src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java')
require('isModLoaded("sodium")' in plugin and 'isModLoaded("iris")' in plugin, 'optional mixin gates absent')
binding = read('src/main/java/com/metallum/client/metal/render/MetalRenderPass.java')
require('import com.mojang.renderpearl.util.TextureViewAndSampler;' in binding, 'upstream engine texture/sampler pair was lost')
require('if (textureView.texture() instanceof MetalGpuTexture metalTexture)' in binding, 'upstream non-backend texture type defense was lost')
require('flushPendingClear((MetalGpuTexture) textureView.texture())' not in binding, 'foreign texture is cast before defensive clear')
for path in ROOT.joinpath('.github/workflows').glob('*.yml'):
    if path.name not in ('minecraft-client-e2e.yml', 'minecraft-26.3-migration.yml', 'metal-capabilities.yml'): continue
    workflow = path.read_text()
    require('26.3-fabric-dev' in workflow and '26.3-Fabric' not in workflow, 'wrong upstream target branch')
    require('self-hosted' not in workflow and 'pull_request_target' not in workflow, 'unsafe untrusted-code execution trigger')
    require('METALLUM_SOURCE_SHA' in workflow and 'contents: read' in workflow, 'exact-head or read-only permission contract missing')
physical = read('scripts/agent/run_metal4_main_p1_physical_performance.sh')
require('minecraftNativeRenderEfficiencyValidation' not in physical, 'physical timing still invokes dev classes')
require('runProductionPhysicalPerformance' in physical and 'check_production_identity.py' in physical, 'physical production path has no observed identity gate')
print('Upstream migration source contracts: PASS')
