from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}\n--- anchor ---\n{old}")
    p.write_text(text.replace(old, new, 1))


swift = "src/main/native/MetallumNative.swift"
replace_once(
    swift,
    "private final class TerrainGpuVisibilityProbeOwner {\n    let epoch: UInt64\n",
    "private final class TerrainGpuVisibilityProbeOwner {\n"
    "    // Value-only lease identity avoids retaining the lease/context and\n"
    "    // forming a command-buffer completion cycle. The visible ICB must be\n"
    "    // authored from the exact lease that produced this in-flight bitset.\n"
    "    let leaseIdentity: ObjectIdentifier\n"
    "    let epoch: UInt64\n",
)
replace_once(
    swift,
    "    init(\n        epoch: UInt64,\n",
    "    init(\n        leaseIdentity: ObjectIdentifier,\n        epoch: UInt64,\n",
)
replace_once(
    swift,
    "    ) {\n        self.epoch = epoch\n        self.candidateCount = candidateCount\n",
    "    ) {\n        self.leaseIdentity = leaseIdentity\n        self.epoch = epoch\n        self.candidateCount = candidateCount\n",
)
replace_once(
    swift,
    "    let owner = TerrainGpuVisibilityProbeOwner(\n        epoch: epoch,\n",
    "    let owner = TerrainGpuVisibilityProbeOwner(\n        leaseIdentity: ObjectIdentifier(bridge.lease),\n        epoch: epoch,\n",
)
replace_once(
    swift,
    "    guard let visibilityOwner = retained as? TerrainGpuVisibilityProbeOwner,\n"
    "          visibilityOwner.epoch == expectedEpoch,\n"
    "          visibilityOwner.candidateCount > 0 else {\n",
    "    guard let visibilityOwner = retained as? TerrainGpuVisibilityProbeOwner,\n"
    "          visibilityOwner.leaseIdentity == ObjectIdentifier(bridge.lease),\n"
    "          visibilityOwner.epoch == expectedEpoch,\n"
    "          visibilityOwner.candidateCount > 0 else {\n",
)

test = "src/test/native/Metal4PipelinePathTest.swift"
call_anchor = '''    guard let negativeIndexBuffer = negativeIndexValues.withUnsafeBytes({ bytes in
        device.makeBuffer(bytes: bytes.baseAddress!, length: bytes.count, options: .storageModeShared)
    }) else {
        try fail("could not allocate negative-base terrain ICB index buffer")
    }

'''
replace_once(
    test,
    call_anchor,
    call_anchor
    + '''    try runTerrainGpuVisibleIcbReadbackTest(
        device: device,
        queue: queue,
        pipeline: pipeline,
        indexBuffer: indexBuffer
    )

''',
)

fixture_anchor = '''/// Exercises visibility bitset -> prefix/compact on real Metal 4 command
/// buffers. Each fixture uses the same native command-buffer path and checks
/// exact tail-masked words, count and stable ascending candidate indices. The
/// result remains value-only and does not become draw-authoritative.
'''
fixture = r'''@available(macOS 26.0, *)
private func runTerrainGpuVisibleIcbReadbackTest(
    device: MTLDevice,
    queue: MTLCommandQueue,
    pipeline: MTLRenderPipelineState,
    indexBuffer: MTLBuffer
) throws {
    metallum_set_terrain_gpu_encode_enabled(1)
    defer { metallum_set_terrain_gpu_encode_enabled(0) }

    let matrix: [Float] = [
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    ]
    let uint32IndexValues: [UInt32] = [0, 1, 2]
    guard let uint32IndexBuffer = uint32IndexValues.withUnsafeBytes({ bytes in
        device.makeBuffer(bytes: bytes.baseAddress!, length: bytes.count, options: .storageModeShared)
    }) else {
        try fail("could not allocate uint32 terrain visible ICB index buffer")
    }

    func runCase(
        label: String,
        visibleIndices: Set<Int>,
        expectedPixel: [UInt8],
        indexType: MTLIndexType,
        indexBuffer: MTLBuffer,
        testCrossLease: Bool = false
    ) throws {
        let candidates: [Float] = (0..<8).flatMap { index in
            if visibleIndices.contains(index) {
                return [-0.5 as Float, -0.5, -0.5, 0.5, 0.5, 0.5, 0.5, 0.0]
            }
            return [2.0 as Float, -0.5, -0.5, 3.0, 0.5, 0.5, 3.0, 0.0]
        }
        let target = try makeTarget(device: device, label: label + " target")
        guard let commandBuffer = label.withCString({ name in
            metallum_MTLCommandQueue_makeCommandBuffer(queue, name)
        }) else {
            try fail("could not allocate \(label) command buffer")
        }
        defer { metallum_release_object(commandBuffer) }
        guard let producer = metallum_MTLCommandBuffer_makeRenderCommandEncoder(
            commandBuffer, target, nil, 8, 8, 1, 0, 0, 0, 1, 0, 1
        ) else {
            try fail("could not create \(label) producer encoder")
        }
        metallum_MTLRenderCommandEncoder_setRenderPipelineState(producer, pipeline)
        metallum_MTLCommandEncoder_endEncoding(producer)
        defer { metallum_release_object(producer) }

        let probe: UnsafeMutableRawPointer? = candidates.withUnsafeBufferPointer { values in
            matrix.withUnsafeBufferPointer { matrixValues in
                guard let valuesBase = values.baseAddress,
                      let matrixBase = matrixValues.baseAddress else { return nil }
                return metallum_MTLDevice_createTerrainGpuVisibilityProbe(
                    producer,
                    device,
                    UnsafeRawPointer(valuesBase).assumingMemoryBound(to: UInt8.self),
                    matrixBase,
                    8,
                    9000
                )
            }
        }
        guard let probe else {
            try fail("could not create \(label) visibility owner")
        }

        // Source order is deliberately [candidate 7, candidate 2, candidate 7].
        // Candidate indices are lookup keys only; source ordinals remain ICB slots.
        let commands: [Int32] = [
            3, 1, 0, 0, 0,
            3, 1, 0, 0, 0,
            3, 1, 0, -1, 0
        ]
        let candidateBySource: [Int32] = [7, 2, 7]
        let resident: UnsafeMutableRawPointer? = commands.withUnsafeBufferPointer { draws in
            candidateBySource.withUnsafeBufferPointer { mapping in
                metallum_MTLDevice_createTerrainVisibleGpuIndexedIcb(
                    producer, device, .triangle, indexType, indexBuffer, pipeline,
                    draws.baseAddress, mapping.baseAddress, 3, probe, 9000
                )
            }
        }
        guard let resident else {
            metallum_release_object(probe)
            try fail("could not create \(label) sparse visible ICB")
        }

        if testCrossLease {
            guard let otherCommandBuffer = (label + " wrong lease").withCString({ name in
                metallum_MTLCommandQueue_makeCommandBuffer(queue, name)
            }), let wrongEncoder = metallum_MTLCommandBuffer_makeRenderCommandEncoder(
                otherCommandBuffer, target, nil, 8, 8, 0, 0, 0, 0, 1, 0, 1
            ) else {
                metallum_release_object(resident)
                metallum_release_object(probe)
                try fail("could not allocate \(label) cross-lease rejection fixture")
            }
            metallum_MTLRenderCommandEncoder_setRenderPipelineState(wrongEncoder, pipeline)
            metallum_MTLCommandEncoder_endEncoding(wrongEncoder)
            let rejected: UnsafeMutableRawPointer? = commands.withUnsafeBufferPointer { draws in
                candidateBySource.withUnsafeBufferPointer { mapping in
                    metallum_MTLDevice_createTerrainVisibleGpuIndexedIcb(
                        wrongEncoder, device, .triangle, indexType, indexBuffer, pipeline,
                        draws.baseAddress, mapping.baseAddress, 3, probe, 9000
                    )
                }
            }
            try check(rejected == nil, "\(label) accepted a different command-buffer lease")
            if let rejected { metallum_release_object(rejected) }
            metallum_release_object(wrongEncoder)
            metallum_release_object(otherCommandBuffer)
        }

        // Drop the external probe reference before execute. The visible ICB
        // owner must retain the probe-backed visibility buffer through completion.
        metallum_release_object(probe)
        guard let consumer = metallum_MTLCommandBuffer_makeRenderCommandEncoder(
            commandBuffer, target, nil, 8, 8, 0, 0, 0, 0, 1, 0, 1
        ) else {
            metallum_release_object(resident)
            try fail("could not create \(label) consumer encoder")
        }
        metallum_MTLRenderCommandEncoder_setRenderPipelineState(consumer, pipeline)
        try check(
            metallum_MTLRenderCommandEncoder_executeTerrainIcb(consumer, resident, 3) != 0,
            "\(label) sparse visible ICB execute failed"
        )
        metallum_MTLCommandEncoder_endEncoding(consumer)
        metallum_release_object(consumer)
        metallum_MTLCommandBuffer_commit(commandBuffer)
        try check(
            metallum_MTLCommandBuffer_waitUntilCompleted(commandBuffer, 5_000) == 0,
            "\(label) command buffer did not complete"
        )
        try check(
            metallum_MTLCommandBuffer_completedSuccessfully(commandBuffer) != 0,
            "\(label) command buffer failed"
        )
        var pixel = [UInt8](repeating: 0, count: 4)
        target.getBytes(&pixel, bytesPerRow: 4,
                        from: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0)
        try check(pixel == expectedPixel,
                  "\(label) pixel \(pixel) differed from expected \(expectedPixel)")
        metallum_release_object(resident)
    }

    try runCase(
        label: "terrain visible ICB sparse source slots",
        visibleIndices: [7], expectedPixel: [64, 128, 191, 255],
        indexType: .uint16, indexBuffer: indexBuffer, testCrossLease: true
    )
    try runCase(
        label: "terrain visible ICB zero visible",
        visibleIndices: [], expectedPixel: [0, 0, 0, 255],
        indexType: .uint16, indexBuffer: indexBuffer
    )
    try runCase(
        label: "terrain visible ICB uint32 negative-base",
        visibleIndices: [7], expectedPixel: [64, 128, 191, 255],
        indexType: .uint32, indexBuffer: uint32IndexBuffer
    )
    print("Terrain visible GPU ICB: sparse source slots, zero-visible reset, cross-lease rejection and owner lifetime passed")
}

'''
replace_once(test, fixture_anchor, fixture + fixture_anchor)
print("terrain visible native lease/fixture patch applied")
