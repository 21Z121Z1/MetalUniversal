import runpy
from pathlib import Path

SCRIPT = Path('.github/scripts/apply_metalfx_semantic_contracts_20260910.py')
runpy.run_path(str(SCRIPT), run_name='__main__')

# The v1 generator used a raw triple-quoted string with a line-continuation marker.
# In a raw string that marker is literal, so strip the known generated leading "\\\n"
# before any compiler sees the generated Java source. Keep this wrapper narrow and
# fail if the generator output shape changes unexpectedly.
for target in (
    Path('src/main/java/com/metallum/client/metal/render/MetalSyntheticExactMotion.java'),
    Path('src/test/java/com/metallum/client/metal/render/MetalSyntheticExactMotionTest.java'),
    Path('src/test/java/com/metallum/client/metal/render/MetalFxTemporalExposureContractTest.java'),
):
    text = target.read_text()
    if not text.startswith('\\\n'):
        raise SystemExit(f'{target}: expected generated leading raw-string backslash')
    target.write_text(text[2:])
