from pathlib import Path

path = Path('src/main/native/MetallumNative.swift')
text = path.read_text()
old = '''    for index in 0..<31 {
        let attribute = descriptor.attributes[index]
        hasher.combine(attribute.format.rawValue)
        hasher.combine(attribute.offset)
        hasher.combine(attribute.bufferIndex)
        let layout = descriptor.layouts[index]
        hasher.combine(layout.stride)
        hasher.combine(layout.stepFunction.rawValue)
        hasher.combine(layout.stepRate)
    }
'''
new = '''    for index in 0..<31 {
        if let attribute = descriptor.attributes[index] {
            hasher.combine(attribute.format.rawValue)
            hasher.combine(attribute.offset)
            hasher.combine(attribute.bufferIndex)
        } else {
            hasher.combine(-1)
        }
        if let layout = descriptor.layouts[index] {
            hasher.combine(layout.stride)
            hasher.combine(layout.stepFunction.rawValue)
            hasher.combine(layout.stepRate)
        } else {
            hasher.combine(-1)
        }
    }
'''
if text.count(old) != 1:
    raise SystemExit(f'optional descriptor loop: expected one match, got {text.count(old)}')
path.write_text(text.replace(old, new, 1))
