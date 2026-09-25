#!/usr/bin/env python3
"""Check upstream version and optional-adapter invariants without starting Minecraft."""
from pathlib import Path
import json
import subprocess
ROOT = Path(__file__).resolve().parents[2]
def props(path):
    return dict(x.split('=', 1) for x in path.read_text().splitlines() if '=' in x and not x.startswith('#'))
p = props(ROOT / 'gradle.properties')
for key, value in {'minecraft_version':'26.3','loader_version':'0.19.5','loom_version':'1.18.2',
                   'sodium_version':'mc26.3-0.9.2-fabric','mod_version':'1.0.4','maven_group':'com.metallum'}.items():
    assert p[key] == value, (key, p.get(key), value)
child = props(ROOT / '.github/ci/minecraft-e2e/gradle.properties')
for key in ('minecraft_version','loader_version','loom_version','sodium_version','iris_version'):
    assert child[key] == p[key], (key, 'E2E pin drift')
wrapper = props(ROOT / 'gradle/wrapper/gradle-wrapper.properties')
assert wrapper['distributionUrl'].endswith('/gradle-9.7.0-bin.zip')
manifest = json.loads((ROOT / 'src/main/resources/fabric.mod.json').read_text())
assert manifest['environment'] == 'client'
assert not ({'sodium','iris'} & set(manifest['depends']))
assert manifest['accessWidener'] == 'metallum.accesswidener'
render_pass = (ROOT / 'src/main/java/com/metallum/client/metal/render/MetalRenderPass.java').read_text()
assert 'import com.mojang.renderpearl.util.TextureViewAndSampler;' in render_pass
assert 'instanceof MetalGpuTexture metalTexture' in render_pass
assert 'flushPendingClear((MetalGpuTexture)' not in render_pass
plugin = (ROOT / 'src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java').read_text()
assert 'isModLoaded("sodium")' in plugin and 'isModLoaded("iris")' in plugin
readback = (ROOT / '.github/ci/minecraft-e2e/src/main/java/com/metallum/e2e/MetalReadbackControlGameTest.java').read_text()
assert 'validateRenderer(System.getProperty("metallum.ci.rendererMode", "")' in readback
assert 'FrameWorkloads.validateProducer(mode, sodium, iris)' in readback
assert 'metallum.ci.noOptionalMods' not in readback
for name in ('minecraft-client-e2e.yml','minecraft-26.3-migration.yml','metal-capabilities.yml'):
    workflow = (ROOT / '.github/workflows' / name).read_text()
    assert '26.3-fabric-dev' in workflow and 'pull_request_target' not in workflow
    assert 'self-hosted' not in workflow
print('upstream pins / RenderPearl / optional adapters / workflow isolation: PASS')
