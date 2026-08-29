from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one match, got {count}')
    return text.replace(old, new, 1)

path = Path('src/main/native/MetallumNative.swift')
text = path.read_text()
text = once(
    text,
    '    static var terrainVisibilityPipelines: [TerrainVisibilityComputePipelineKey: TerrainVisibilityCompactionPipelines] = [:]\n',
    '    static var terrainVisibilityPipelines: [TerrainVisibilityComputePipelineKey: TerrainVisibilityCompactionPipelines] = [:]\n'
    '    // Shipping visible-ICB only needs the frustum/bitset kernel. Keep a\n'
    '    // separate cache so first use does not compile four scan/scatter PSOs\n'
    '    // that the non-diagnostic path never dispatches.\n'
    '    static var terrainVisibilityOnlyPipelines: [TerrainVisibilityComputePipelineKey: MTLComputePipelineState] = [:]\n',
    'visibility-only cache'
)
owner_anchor = '''    NativeState.terrainVisibilityPipelines[key] = pipeline
    return pipeline
}

@available(macOS 26.0, iOS 26.0, *)
private final class TerrainGpuVisibilityProbeOwner {
'''
helper = '''    NativeState.terrainVisibilityPipelines[key] = pipeline
    return pipeline
}

@available(macOS 26.0, iOS 26.0, *)
private func terrainVisibilityOnlyPipeline(
    device: MTLDevice,
    source: String
) -> MTLComputePipelineState? {
    let key = TerrainVisibilityComputePipelineKey(deviceAddress: objectAddress(device))
    NativeState.terrainVisibilityPipelineLock.lock()
    defer { NativeState.terrainVisibilityPipelineLock.unlock() }
    if let cached = NativeState.terrainVisibilityOnlyPipelines[key] {
        return cached
    }
    let pipeline: MTLComputePipelineState? = NativeState.onCompilerThread {
        do {
            let library = try device.makeLibrary(source: source, options: nil)
            guard let visibility = library.makeFunction(name: "metallum_terrain_gpu_visibility_probe") else {
                return nil
            }
            return try device.makeComputePipelineState(function: visibility)
        } catch {
            return nil
        }
    }
    if let pipeline {
        NativeState.terrainVisibilityOnlyPipelines[key] = pipeline
    }
    return pipeline
}

@available(macOS 26.0, iOS 26.0, *)
private final class TerrainGpuVisibilityProbeOwner {
'''
text = once(text, owner_anchor, helper, 'visibility-only helper insertion')
old_guard = '''    guard let pipeline = terrainVisibilityComputePipelines(
        device: device,
        source: terrainVisibilityMslSource()
    ), pipeline.visibility.maxTotalThreadsPerThreadgroup >= blockWidth,
          pipeline.blockScan.maxTotalThreadsPerThreadgroup >= blockWidth,
          pipeline.blockSumsScan.maxTotalThreadsPerThreadgroup >= blockWidth,
          pipeline.groupScan.maxTotalThreadsPerThreadgroup >= blockWidth,
          pipeline.scatter.maxTotalThreadsPerThreadgroup >= blockWidth,
          let computeEncoder = bridge.lease.commandBuffer.makeComputeCommandEncoder() else {
        return nil
    }
'''
new_guard = '''    let visibilitySource = terrainVisibilityMslSource()
    let visibilityPipeline: MTLComputePipelineState
    let compactionPipelines: TerrainVisibilityCompactionPipelines?
    if compact {
        guard let pipelines = terrainVisibilityComputePipelines(
            device: device,
            source: visibilitySource
        ), pipelines.visibility.maxTotalThreadsPerThreadgroup >= blockWidth,
              pipelines.blockScan.maxTotalThreadsPerThreadgroup >= blockWidth,
              pipelines.blockSumsScan.maxTotalThreadsPerThreadgroup >= blockWidth,
              pipelines.groupScan.maxTotalThreadsPerThreadgroup >= blockWidth,
              pipelines.scatter.maxTotalThreadsPerThreadgroup >= blockWidth else {
            return nil
        }
        visibilityPipeline = pipelines.visibility
        compactionPipelines = pipelines
    } else {
        guard let pipeline = terrainVisibilityOnlyPipeline(
            device: device,
            source: visibilitySource
        ), pipeline.maxTotalThreadsPerThreadgroup >= blockWidth else {
            return nil
        }
        visibilityPipeline = pipeline
        compactionPipelines = nil
    }
    guard let computeEncoder = bridge.lease.commandBuffer.makeComputeCommandEncoder() else {
        return nil
    }
'''
text = once(text, old_guard, new_guard, 'probe pipeline selection')
text = once(
    text,
    '    computeEncoder.setComputePipelineState(pipeline.visibility)\n',
    '    computeEncoder.setComputePipelineState(visibilityPipeline)\n',
    'visibility PSO dispatch'
)
text = once(
    text,
    '    if compact {\n        computeEncoder.barrier(',
    '    if let pipeline = compactionPipelines {\n        computeEncoder.barrier(',
    'compaction PSO unwrap'
)
path.write_text(text)

contract_path = Path('src/test/java/com/metallum/client/metal/render/Metal4TerrainVisibleIcbContractTest.java')
contract = contract_path.read_text()
insert = '''
    @Test
    void visibleOnlyProbeCompilesOnlyItsConsumedPso() throws IOException {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        assertTrue(nativeSource.contains("terrainVisibilityOnlyPipelines"));
        assertTrue(nativeSource.contains("private func terrainVisibilityOnlyPipeline("));
        assertTrue(nativeSource.contains("visibilityPipeline = pipeline"));
        assertTrue(nativeSource.contains("compactionPipelines = nil"));
        assertTrue(nativeSource.contains("if let pipeline = compactionPipelines {"));
    }
'''
pos = contract.rfind('\n}')
if pos < 0:
    raise SystemExit('contract class close missing')
contract_path.write_text(contract[:pos] + insert + contract[pos:])
print('terrain visibility PSO specialization patch applied')
