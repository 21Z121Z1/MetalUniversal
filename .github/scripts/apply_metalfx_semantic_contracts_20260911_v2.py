import runpy
from pathlib import Path

runpy.run_path('.github/scripts/apply_metalfx_semantic_contracts_20260911.py', run_name='__main__')

test = Path('src/test/java/com/metallum/client/metal/render/MetalSyntheticExactMotionTest.java')
text = test.read_text()
old_imports = '''import net.minecraft.world.InteractionHand;\nimport net.minecraft.world.item.ItemStack;\nimport net.minecraft.world.item.Items;\n'''
new_imports = '''import net.minecraft.core.Holder;\nimport net.minecraft.world.InteractionHand;\nimport net.minecraft.world.item.ItemStack;\nimport net.minecraft.world.item.Items;\n'''
if text.count(old_imports) != 1:
    raise SystemExit('unexpected first-person test import shape')
text = text.replace(old_imports, new_imports, 1)

replacements = {
    'new ItemStack(Items.STONE);': 'new ItemStack(Holder.direct(Items.STONE));',
    'new ItemStack(Items.DIRT);': 'new ItemStack(Holder.direct(Items.DIRT));',
    'new ItemStack(Items.STONE, 1);': 'new ItemStack(Holder.direct(Items.STONE), 1);',
    'new ItemStack(Items.STONE, 2);': 'new ItemStack(Holder.direct(Items.STONE), 2);',
}
for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f'missing expected ItemStack fixture: {old}')
    text = text.replace(old, new)

test.write_text(text)
