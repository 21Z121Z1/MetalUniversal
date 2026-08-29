from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

path = Path('src/main/native/MetallumNative.swift')
text = path.read_text()

old_scene = '''@available(macOS 26.0, iOS 26.0, *)
private final class TerrainGpuVisibilitySceneOwner {
    let sceneGeneration: UInt64
    let candidateCount: Int
    let candidateBuffer: MTLBuffer

    init(sceneGeneration: UInt64, candidateCount: Int, candidateBuffer: MTLBuffer) {
        self.sceneGeneration = sceneGeneration
        self.candidateCount = candidateCount
        self.candidateBuffer = candidateBuffer
        residencyTrackCreated(candidateBuffer)
    }

    deinit {
        residencyTrackReleased(rawPointer(candidateBuffer))
    }
}
'''
new_scene = '''@available(macOS 26.0, iOS 26.0, *)
private final class TerrainGpuVisibilitySceneOwner {
    static let inFlightSlotCount = 3

    final class FrameSlot {
        let frameBuffer: MTLBuffer
        let visibilityBuffer: MTLBuffer
        let countersBuffer: MTLBuffer
        let prefixLocalBuffer: MTLBuffer
        let blockSumsBuffer: MTLBuffer
        let blockOffsetsBuffer: MTLBuffer
        let groupSumsBuffer: MTLBuffer
        let groupOffsetsBuffer: MTLBuffer
        let compactedIndicesBuffer: MTLBuffer
        let compactedCountBuffer: MTLBuffer
        let paramsBuffer: MTLBuffer
        let arguments: MTL4ArgumentTable

        init?(
            device: MTLDevice,
            candidateBuffer: MTLBuffer,
            candidateCount: Int,
            wordCount: Int,
            blockCount: Int,
            groupCount: Int,
            slotIndex: Int
        ) {
            guard let frameBuffer = device.makeBuffer(length: 96, options: .storageModeShared),
                  let visibilityBuffer = device.makeBuffer(
                    length: wordCount * MemoryLayout<UInt32>.stride, options: .storageModeShared
                  ),
                  let countersBuffer = device.makeBuffer(
                    length: 2 * MemoryLayout<UInt32>.stride, options: .storageModeShared
                  ),
                  let prefixLocalBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
                  let blockSumsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
                  let blockOffsetsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
                  let groupSumsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
                  let groupOffsetsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
                  let compactedIndicesBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
                  let compactedCountBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
                  let paramsBuffer = device.makeBuffer(length: 12, options: .storageModeShared) else {
                return nil
            }
            let descriptor = MTL4ArgumentTableDescriptor()
            descriptor.maxBufferBindCount = 12
            descriptor.initializeBindings = true
            descriptor.supportAttributeStrides = false
            descriptor.label = "Metallum Terrain Visibility Scene Arguments \\(slotIndex)"
            guard let arguments = try? device.makeArgumentTable(descriptor: descriptor) else {
                return nil
            }
            frameBuffer.label = "Metallum Terrain Visibility Frame \\(slotIndex)"
            visibilityBuffer.label = "Metallum Terrain Visibility Bits \\(slotIndex)"
            countersBuffer.label = "Metallum Terrain Visibility Counters \\(slotIndex)"
            let params = paramsBuffer.contents().assumingMemoryBound(to: UInt32.self)
            params[0] = UInt32(candidateCount)
            params[1] = UInt32(blockCount)
            params[2] = UInt32(groupCount)
            arguments.setAddress(candidateBuffer.gpuAddress, index: 0)
            arguments.setAddress(frameBuffer.gpuAddress, index: 1)
            arguments.setAddress(visibilityBuffer.gpuAddress, index: 2)
            arguments.setAddress(countersBuffer.gpuAddress, index: 3)
            arguments.setAddress(prefixLocalBuffer.gpuAddress, index: 4)
            arguments.setAddress(blockSumsBuffer.gpuAddress, index: 5)
            arguments.setAddress(blockOffsetsBuffer.gpuAddress, index: 6)
            arguments.setAddress(groupSumsBuffer.gpuAddress, index: 7)
            arguments.setAddress(groupOffsetsBuffer.gpuAddress, index: 8)
            arguments.setAddress(compactedIndicesBuffer.gpuAddress, index: 9)
            arguments.setAddress(compactedCountBuffer.gpuAddress, index: 10)
            arguments.setAddress(paramsBuffer.gpuAddress, index: 11)
            self.frameBuffer = frameBuffer
            self.visibilityBuffer = visibilityBuffer
            self.countersBuffer = countersBuffer
            self.prefixLocalBuffer = prefixLocalBuffer
            self.blockSumsBuffer = blockSumsBuffer
            self.blockOffsetsBuffer = blockOffsetsBuffer
            self.groupSumsBuffer = groupSumsBuffer
            self.groupOffsetsBuffer = groupOffsetsBuffer
            self.compactedIndicesBuffer = compactedIndicesBuffer
            self.compactedCountBuffer = compactedCountBuffer
            self.paramsBuffer = paramsBuffer
            self.arguments = arguments
        }

        var buffers: [MTLBuffer] {
            [frameBuffer, visibilityBuffer, countersBuffer, prefixLocalBuffer,
             blockSumsBuffer, blockOffsetsBuffer, groupSumsBuffer, groupOffsetsBuffer,
             compactedIndicesBuffer, compactedCountBuffer, paramsBuffer]
        }
    }

    let sceneGeneration: UInt64
    let candidateCount: Int
    let candidateBuffer: MTLBuffer
    let wordCount: Int
    let blockCount: Int
    let groupCount: Int
    private let slots: [FrameSlot]

    init?(
        device: MTLDevice,
        sceneGeneration: UInt64,
        candidateCount: Int,
        candidateBuffer: MTLBuffer
    ) {
        guard candidateCount > 0, candidateCount <= Int.max - 31 else { return nil }
        let wordCount = (candidateCount + 31) / 32
        let blockWidth = 256
        guard candidateCount <= Int.max - (blockWidth - 1) else { return nil }
        let blockCount = (candidateCount + blockWidth - 1) / blockWidth
        let groupCount = max(1, (blockCount + blockWidth - 1) / blockWidth)
        var created: [FrameSlot] = []
        created.reserveCapacity(Self.inFlightSlotCount)
        for slotIndex in 0..<Self.inFlightSlotCount {
            guard let slot = FrameSlot(
                device: device,
                candidateBuffer: candidateBuffer,
                candidateCount: candidateCount,
                wordCount: wordCount,
                blockCount: blockCount,
                groupCount: groupCount,
                slotIndex: slotIndex
            ) else { return nil }
            created.append(slot)
        }
        self.sceneGeneration = sceneGeneration
        self.candidateCount = candidateCount
        self.candidateBuffer = candidateBuffer
        self.wordCount = wordCount
        self.blockCount = blockCount
        self.groupCount = groupCount
        self.slots = created
        residencyTrackCreated(candidateBuffer)
        for slot in created {
            for buffer in slot.buffers { residencyTrackCreated(buffer) }
        }
    }

    func frameSlot(at index: Int) -> FrameSlot? {
        guard index >= 0, index < slots.count else { return nil }
        return slots[index]
    }

    deinit {
        residencyTrackReleased(rawPointer(candidateBuffer))
        for slot in slots {
            for buffer in slot.buffers { residencyTrackReleased(rawPointer(buffer)) }
        }
    }
}
'''
text = once(text, old_scene, new_scene, 'scene owner ring')

text = once(
    text,
    '    let ownsCandidateBuffer: Bool\n    let matrixBuffer: MTLBuffer\n',
    '    let ownsCandidateBuffer: Bool\n    let ownsProbeBuffers: Bool\n    let matrixBuffer: MTLBuffer\n',
    'probe ownership field'
)
text = once(
    text,
    '        ownsCandidateBuffer: Bool = true,\n        matrixBuffer: MTLBuffer,\n',
    '        ownsCandidateBuffer: Bool = true,\n        ownsProbeBuffers: Bool = true,\n        matrixBuffer: MTLBuffer,\n',
    'probe ownership arg'
)
text = once(
    text,
    '        self.ownsCandidateBuffer = ownsCandidateBuffer\n        self.matrixBuffer = matrixBuffer\n',
    '        self.ownsCandidateBuffer = ownsCandidateBuffer\n        self.ownsProbeBuffers = ownsProbeBuffers\n        self.matrixBuffer = matrixBuffer\n',
    'probe ownership assignment'
)
old_track = '''        if ownsCandidateBuffer { residencyTrackCreated(candidateBuffer) }
        residencyTrackCreated(matrixBuffer)
        residencyTrackCreated(visibilityBuffer)
        residencyTrackCreated(countersBuffer)
        residencyTrackCreated(prefixLocalBuffer)
        residencyTrackCreated(blockSumsBuffer)
        residencyTrackCreated(blockOffsetsBuffer)
        residencyTrackCreated(groupSumsBuffer)
        residencyTrackCreated(groupOffsetsBuffer)
        residencyTrackCreated(compactedIndicesBuffer)
        residencyTrackCreated(compactedCountBuffer)
        residencyTrackCreated(paramsBuffer)
'''
new_track = '''        if ownsCandidateBuffer { residencyTrackCreated(candidateBuffer) }
        if ownsProbeBuffers {
            residencyTrackCreated(matrixBuffer)
            residencyTrackCreated(visibilityBuffer)
            residencyTrackCreated(countersBuffer)
            residencyTrackCreated(prefixLocalBuffer)
            residencyTrackCreated(blockSumsBuffer)
            residencyTrackCreated(blockOffsetsBuffer)
            residencyTrackCreated(groupSumsBuffer)
            residencyTrackCreated(groupOffsetsBuffer)
            residencyTrackCreated(compactedIndicesBuffer)
            residencyTrackCreated(compactedCountBuffer)
            residencyTrackCreated(paramsBuffer)
        }
'''
text = once(text, old_track, new_track, 'probe residency track')
old_release = '''        if ownsCandidateBuffer { residencyTrackReleased(rawPointer(candidateBuffer)) }
        residencyTrackReleased(rawPointer(matrixBuffer))
        residencyTrackReleased(rawPointer(visibilityBuffer))
        residencyTrackReleased(rawPointer(countersBuffer))
        residencyTrackReleased(rawPointer(prefixLocalBuffer))
        residencyTrackReleased(rawPointer(blockSumsBuffer))
        residencyTrackReleased(rawPointer(blockOffsetsBuffer))
        residencyTrackReleased(rawPointer(groupSumsBuffer))
        residencyTrackReleased(rawPointer(groupOffsetsBuffer))
        residencyTrackReleased(rawPointer(compactedIndicesBuffer))
        residencyTrackReleased(rawPointer(compactedCountBuffer))
        residencyTrackReleased(rawPointer(paramsBuffer))
'''
new_release = '''        if ownsCandidateBuffer { residencyTrackReleased(rawPointer(candidateBuffer)) }
        if ownsProbeBuffers {
            residencyTrackReleased(rawPointer(matrixBuffer))
            residencyTrackReleased(rawPointer(visibilityBuffer))
            residencyTrackReleased(rawPointer(countersBuffer))
            residencyTrackReleased(rawPointer(prefixLocalBuffer))
            residencyTrackReleased(rawPointer(blockSumsBuffer))
            residencyTrackReleased(rawPointer(blockOffsetsBuffer))
            residencyTrackReleased(rawPointer(groupSumsBuffer))
            residencyTrackReleased(rawPointer(groupOffsetsBuffer))
            residencyTrackReleased(rawPointer(compactedIndicesBuffer))
            residencyTrackReleased(rawPointer(compactedCountBuffer))
            residencyTrackReleased(rawPointer(paramsBuffer))
        }
'''
text = once(text, old_release, new_release, 'probe residency release')

old_create_owner = '''    return retainedPointer(TerrainGpuVisibilitySceneOwner(
        sceneGeneration: sceneGeneration,
        candidateCount: count,
        candidateBuffer: buffer
    ))
'''
new_create_owner = '''    guard let owner = TerrainGpuVisibilitySceneOwner(
        device: device,
        sceneGeneration: sceneGeneration,
        candidateCount: count,
        candidateBuffer: buffer
    ) else { return nil }
    return retainedPointer(owner)
'''
text = once(text, old_create_owner, new_create_owner, 'scene owner creation')

start = text.index('@_cdecl("metallum_MTLDevice_createTerrainGpuVisibilitySceneProbe")')
end = text.index('\n/// Dispatches the value-only visibility probe', start)
segment = text[start:end]
old_alloc_start = '''    guard count <= Int.max - 31 else { return nil }
    let wordCount = (count + 31) / 32
    let blockWidth = 256
    guard count <= Int.max - (blockWidth - 1) else { return nil }
    let blockCount = (count + blockWidth - 1) / blockWidth
    let groupCount = max(1, (blockCount + blockWidth - 1) / blockWidth)
    guard blockCount > 0,
          wordCount <= Int.max / MemoryLayout<UInt32>.stride else { return nil }

    guard let frameBuffer = device.makeBuffer(
        bytes: UnsafeRawPointer(packedFrame), length: 96, options: .storageModeShared
    ), let visibilityBuffer = device.makeBuffer(
        length: wordCount * MemoryLayout<UInt32>.stride, options: .storageModeShared
    ), let countersBuffer = device.makeBuffer(
        length: 2 * MemoryLayout<UInt32>.stride, options: .storageModeShared
    ), let prefixLocalBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let blockSumsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let blockOffsetsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let groupSumsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let groupOffsetsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let compactedIndicesBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let compactedCountBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let paramsBuffer = device.makeBuffer(length: 12, options: .storageModeShared) else {
        return nil
    }
    visibilityBuffer.contents().assumingMemoryBound(to: UInt32.self)
        .initialize(repeating: 0, count: wordCount)
    countersBuffer.contents().assumingMemoryBound(to: UInt32.self)
        .initialize(repeating: 0, count: 2)
    let params = paramsBuffer.contents().assumingMemoryBound(to: UInt32.self)
    params[0] = UInt32(count)
    params[1] = UInt32(blockCount)
    params[2] = UInt32(groupCount)
'''
new_alloc_start = '''    let wordCount = scene.wordCount
    let blockCount = scene.blockCount
    let groupCount = scene.groupCount
    let blockWidth = 256
    guard let slot = scene.frameSlot(at: bridge.lease.slotIndex) else { return nil }
    slot.frameBuffer.contents().copyMemory(from: UnsafeRawPointer(packedFrame), byteCount: 96)
    slot.visibilityBuffer.contents().assumingMemoryBound(to: UInt32.self)
        .initialize(repeating: 0, count: wordCount)
    slot.countersBuffer.contents().assumingMemoryBound(to: UInt32.self)
        .initialize(repeating: 0, count: 2)
'''
segment = once(segment, old_alloc_start, new_alloc_start, 'scene per-frame allocation')
old_arguments = '''    let descriptor = MTL4ArgumentTableDescriptor()
    descriptor.maxBufferBindCount = 12
    descriptor.initializeBindings = true
    descriptor.supportAttributeStrides = false
    guard let arguments = try? device.makeArgumentTable(descriptor: descriptor) else {
        computeEncoder.endEncoding()
        return nil
    }
    arguments.setAddress(scene.candidateBuffer.gpuAddress, index: 0)
    arguments.setAddress(frameBuffer.gpuAddress, index: 1)
    arguments.setAddress(visibilityBuffer.gpuAddress, index: 2)
    arguments.setAddress(countersBuffer.gpuAddress, index: 3)
    arguments.setAddress(prefixLocalBuffer.gpuAddress, index: 4)
    arguments.setAddress(blockSumsBuffer.gpuAddress, index: 5)
    arguments.setAddress(blockOffsetsBuffer.gpuAddress, index: 6)
    arguments.setAddress(groupSumsBuffer.gpuAddress, index: 7)
    arguments.setAddress(groupOffsetsBuffer.gpuAddress, index: 8)
    arguments.setAddress(compactedIndicesBuffer.gpuAddress, index: 9)
    arguments.setAddress(compactedCountBuffer.gpuAddress, index: 10)
    arguments.setAddress(paramsBuffer.gpuAddress, index: 11)
    computeEncoder.setArgumentTable(arguments)
'''
segment = once(segment, old_arguments, '    computeEncoder.setArgumentTable(slot.arguments)\n', 'scene argument table reuse')
replacements = {
    '        matrixBuffer: frameBuffer,\n': '        matrixBuffer: slot.frameBuffer,\n',
    '        visibilityBuffer: visibilityBuffer,\n': '        visibilityBuffer: slot.visibilityBuffer,\n',
    '        countersBuffer: countersBuffer,\n': '        countersBuffer: slot.countersBuffer,\n',
    '        prefixLocalBuffer: prefixLocalBuffer,\n': '        prefixLocalBuffer: slot.prefixLocalBuffer,\n',
    '        blockSumsBuffer: blockSumsBuffer,\n': '        blockSumsBuffer: slot.blockSumsBuffer,\n',
    '        blockOffsetsBuffer: blockOffsetsBuffer,\n': '        blockOffsetsBuffer: slot.blockOffsetsBuffer,\n',
    '        groupSumsBuffer: groupSumsBuffer,\n': '        groupSumsBuffer: slot.groupSumsBuffer,\n',
    '        groupOffsetsBuffer: groupOffsetsBuffer,\n': '        groupOffsetsBuffer: slot.groupOffsetsBuffer,\n',
    '        compactedIndicesBuffer: compactedIndicesBuffer,\n': '        compactedIndicesBuffer: slot.compactedIndicesBuffer,\n',
    '        compactedCountBuffer: compactedCountBuffer,\n': '        compactedCountBuffer: slot.compactedCountBuffer,\n',
    '        paramsBuffer: paramsBuffer,\n': '        paramsBuffer: slot.paramsBuffer,\n',
}
for old, new in replacements.items():
    segment = once(segment, old, new, f'scene owner slot buffer {old.strip()}')
segment = once(
    segment,
    '        ownsCandidateBuffer: false,\n        matrixBuffer: slot.frameBuffer,\n',
    '        ownsCandidateBuffer: false,\n        ownsProbeBuffers: false,\n        matrixBuffer: slot.frameBuffer,\n',
    'scene borrowed probe buffers'
)
text = text[:start] + segment + text[end:]
path.write_text(text)

contract = Path('src/test/java/com/metallum/client/metal/render/TerrainPersistentGpuSceneFrameSlotContractTest.java')
contract.write_text(r'''package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainPersistentGpuSceneFrameSlotContractTest {
    @Test
    void persistentSceneOwnsOneScratchSetPerMetal4MainQueueSlot() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int ownerStart = source.indexOf("private final class TerrainGpuVisibilitySceneOwner");
        int probeStart = source.indexOf("metallum_MTLDevice_createTerrainGpuVisibilitySceneProbe", ownerStart);
        int legacyStart = source.indexOf("Dispatches the value-only visibility probe", probeStart);
        assertTrue(ownerStart > 0 && probeStart > ownerStart && legacyStart > probeStart);
        String owner = source.substring(ownerStart, probeStart);
        String probe = source.substring(probeStart, legacyStart);
        assertTrue(owner.contains("static let inFlightSlotCount = 3"));
        assertTrue(owner.contains("final class FrameSlot"));
        assertTrue(owner.contains("let arguments: MTL4ArgumentTable"));
        assertTrue(owner.contains("for slotIndex in 0..<Self.inFlightSlotCount"));
        assertTrue(probe.contains("scene.frameSlot(at: bridge.lease.slotIndex)"));
        assertTrue(probe.contains("slot.frameBuffer.contents().copyMemory"));
        assertTrue(probe.contains("computeEncoder.setArgumentTable(slot.arguments)"));
        assertTrue(probe.contains("ownsProbeBuffers: false"));
        assertFalse(probe.contains("device.makeBuffer(length: 96"));
        assertFalse(probe.contains("device.makeArgumentTable(descriptor:"));
    }
}
''')
print('terrain scene frame-slot ring staged')
