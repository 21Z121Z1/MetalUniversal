from pathlib import Path

path = Path("src/main/native/MetallumNative.swift")
text = path.read_text()

old_struct = """private struct PipelineVariantKey: Hashable {
    let deviceAddress: UInt
    let colorFormat: MTLPixelFormat
    let depthFormat: MTLPixelFormat
    let writeColor: Bool
}

private struct SamplerKey: Hashable {
"""
new_struct = """private struct PipelineVariantKey: Hashable {
    let deviceAddress: UInt
    let colorFormat: MTLPixelFormat
    let depthFormat: MTLPixelFormat
    let writeColor: Bool
}

private struct CopyPipelineKey: Hashable {
    let deviceAddress: UInt
    let colorFormat: MTLPixelFormat
}

private struct SamplerKey: Hashable {
"""
if old_struct not in text:
    raise RuntimeError("copy pipeline key insertion anchor not found")
text = text.replace(old_struct, new_struct, 1)

old_map = "    static var copyPipelines: [Int: MTLRenderPipelineState] = [:]"
new_map = "    static var copyPipelines: [CopyPipelineKey: MTLRenderPipelineState] = [:]"
if old_map not in text:
    raise RuntimeError("copy pipeline map anchor not found")
text = text.replace(old_map, new_map, 1)

old_lookup = """private func ensureCopyPipeline(_ device: MTLDevice, _ colorFormat: MTLPixelFormat) -> MTLRenderPipelineState? {
    let key = Int(colorFormat.rawValue)
    if let pipeline = NativeState.copyPipelines[key] {
"""
new_lookup = """private func ensureCopyPipeline(_ device: MTLDevice, _ colorFormat: MTLPixelFormat) -> MTLRenderPipelineState? {
    let key = CopyPipelineKey(
        deviceAddress: objectAddress(device),
        colorFormat: colorFormat
    )
    if let pipeline = NativeState.copyPipelines[key] {
"""
if old_lookup not in text:
    raise RuntimeError("copy pipeline lookup anchor not found")
text = text.replace(old_lookup, new_lookup, 1)

path.write_text(text)
