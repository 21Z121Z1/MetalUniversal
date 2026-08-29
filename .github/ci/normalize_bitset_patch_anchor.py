from pathlib import Path

path = Path('src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java')
text = path.read_text()
old = '''            setTerrainGpuEncodeEnabled = optionalDowncall(
                    lookup,
                    "metallum_set_terrain_gpu_encode_enabled",
                    FunctionDescriptor.ofVoid(INT)
            );
'''
new = '            setTerrainGpuEncodeEnabled = optionalDowncall(lookup, "metallum_set_terrain_gpu_encode_enabled", FunctionDescriptor.ofVoid(INT));\n'
if text.count(old) != 1:
    raise SystemExit(f'expected one multiline terrain GPU setter lookup, got {text.count(old)}')
path.write_text(text.replace(old, new, 1))
print('normalized bitset patch anchor')
