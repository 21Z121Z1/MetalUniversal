from pathlib import Path

patcher = Path('.github/scripts/apply_fishing_hook_line_exact_motion.py')
source = patcher.read_text()
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
    raise SystemExit(f'expected one ambiguous eligibility patch block, got {source.count(old)}')
source = source.replace(old, new, 1)
exec(compile(source, str(patcher), 'exec'), {'__name__': '__main__'})

shader = Path('src/main/resources/assets/metallum/shaders/core/rendertype_lines_previous_motion.vsh')
text = shader.read_text()
text = text.replace('!isfinite(width)', '!isnan(width) && !isinf(width)')
text = text.replace('!isfinite(deltaLength)', '!isnan(deltaLength) && !isinf(deltaLength)')
if 'isfinite(' in text:
    raise SystemExit('unsupported GLSL isfinite remained')
shader.write_text(text)
