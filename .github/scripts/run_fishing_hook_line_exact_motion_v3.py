from pathlib import Path

patcher = Path('.github/scripts/apply_fishing_hook_line_exact_motion.py')
source = patcher.read_text()

# 1) Make FishingHook insertion unique to requiresExactPreviousPositions().
old = """once(p,
     '                || state instanceof FireworkRocketRenderState\\n',
     '                || state instanceof FireworkRocketRenderState\\n                || state instanceof FishingHookRenderState\\n')
"""
new = """once(p,
     '                || state instanceof ThrownItemRenderState\\n'
     '                || state instanceof FireworkRocketRenderState\\n'
     '                || state instanceof ItemClusterRenderState\\n',
     '                || state instanceof ThrownItemRenderState\\n'
     '                || state instanceof FireworkRocketRenderState\\n'
     '                || state instanceof FishingHookRenderState\\n'
     '                || state instanceof ItemClusterRenderState\\n')
"""
if source.count(old) != 1:
    raise SystemExit(f'eligibility patch block count={source.count(old)}')
source = source.replace(old, new, 1)

# 2) Current history test already imports assertArrayEquals. Add the actual missing type import instead.
old = """once(p,
     'import static org.junit.jupiter.api.Assertions.assertEquals;\\n',
     'import static org.junit.jupiter.api.Assertions.assertArrayEquals;\\nimport static org.junit.jupiter.api.Assertions.assertEquals;\\n')
"""
new = """once(p,
     'import com.mojang.blaze3d.vertex.VertexFormat;\\n',
     'import com.mojang.blaze3d.vertex.DefaultVertexFormat;\\nimport com.mojang.blaze3d.vertex.VertexFormat;\\n')
"""
if source.count(old) != 1:
    raise SystemExit(f'history import patch block count={source.count(old)}')
source = source.replace(old, new, 1)
source = source.replace('void previousPositionBindingIsPackedFloat3()', 'void compactPreviousPositionBindingMatchesConfirmedMinecraftAbi()')

exec(compile(source, str(patcher), 'exec'), {'__name__': '__main__'})

shader = Path('src/main/resources/assets/metallum/shaders/core/rendertype_lines_previous_motion.vsh')
text = shader.read_text()
text = text.replace('!isfinite(width)', '!isnan(width) && !isinf(width)')
text = text.replace('!isfinite(deltaLength)', '!isnan(deltaLength) && !isinf(deltaLength)')
if 'isfinite(' in text:
    raise SystemExit('unsupported GLSL isfinite remained')
shader.write_text(text)
