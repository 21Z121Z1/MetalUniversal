import runpy
from pathlib import Path

runpy.run_path('.github/scripts/apply_metalfx_semantic_contracts_20260911.py', run_name='__main__')

test = Path('src/test/java/com/metallum/client/metal/render/MetalSyntheticExactMotionTest.java')
text = test.read_text()
old_imports = '''import net.minecraft.world.InteractionHand;\nimport net.minecraft.world.item.ItemStack;\nimport net.minecraft.world.item.Items;\nimport org.junit.jupiter.api.AfterEach;\nimport org.junit.jupiter.api.Test;\n'''
new_imports = '''import net.minecraft.SharedConstants;\nimport net.minecraft.server.Bootstrap;\nimport net.minecraft.world.InteractionHand;\nimport net.minecraft.world.item.ItemStack;\nimport net.minecraft.world.item.Items;\nimport org.junit.jupiter.api.AfterEach;\nimport org.junit.jupiter.api.BeforeAll;\nimport org.junit.jupiter.api.Test;\n'''
if text.count(old_imports) != 1:
    raise SystemExit('unexpected first-person test import shape')
text = text.replace(old_imports, new_imports, 1)
anchor = '''final class MetalSyntheticExactMotionTest {\n    @AfterEach\n'''
replacement = '''final class MetalSyntheticExactMotionTest {\n    @BeforeAll\n    static void bootstrapMinecraftRegistries() {\n        SharedConstants.tryDetectVersion();\n        Bootstrap.bootStrap();\n    }\n\n    @AfterEach\n'''
if text.count(anchor) != 1:
    raise SystemExit('unexpected first-person test class shape')
test.write_text(text.replace(anchor, replacement, 1))
