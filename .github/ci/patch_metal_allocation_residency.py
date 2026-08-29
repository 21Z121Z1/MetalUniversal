from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)


path = Path("src/main/native/MetallumNative.swift")
text = path.read_text()

text = once(
    text,
    "private func residencyAdd(_ resource: MTLResource) {",
    "private func residencyAdd(_ allocation: any MTLAllocation) {",
    "residency add allocation type",
)
text = once(
    text,
    "    if let texture = resource as? MTLTexture, texture.storageMode == .memoryless {\n        return\n    }\n    set.addAllocation(resource)",
    "    if let texture = allocation as? MTLTexture, texture.storageMode == .memoryless {\n        return\n    }\n    set.addAllocation(allocation)",
    "residency add body",
)
text = once(
    text,
    "private func residencyRemove(_ resource: MTLResource) {",
    "private func residencyRemove(_ allocation: any MTLAllocation) {",
    "residency remove allocation type",
)
text = once(
    text,
    "    set.removeAllocation(resource)\n    NativeState.residencyDirty = true",
    "    set.removeAllocation(allocation)\n    NativeState.residencyDirty = true",
    "residency remove body",
)
# Keep the unversioned entry point erased. MTLAllocation itself is only
# available on macOS 15 / iOS 18, so mentioning it in this function's
# signature would raise the deployment target of every caller.
text = once(
    text,
    "private func residencyTrackCreated(_ resource: MTLResource?) {\n    guard let resource, NativeState.residencySetStorage != nil else { return }\n    if #available(macOS 15.0, iOS 18.0, *) {\n        residencyAdd(resource)\n    }\n}",
    "private func residencyTrackCreated(_ object: AnyObject?) {\n    guard let object, NativeState.residencySetStorage != nil else { return }\n    if #available(macOS 15.0, iOS 18.0, *), let allocation = object as? any MTLAllocation {\n        residencyAdd(allocation)\n    }\n}",
    "created allocation tracker",
)
text = once(
    text,
    "        guard let resource = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as? MTLResource else {\n            return\n        }\n        residencyRemove(resource)",
    "        guard let allocation = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as? any MTLAllocation else {\n            return\n        }\n        residencyRemove(allocation)",
    "released allocation tracker",
)

# The generic render pipeline factory is the authority used by Java. Both the
# Metal 4 compiler path and the Metal 3 fallback need to enter the same queue-
# level residency set before their command buffers are committed.
text = once(
    text,
    "                if !NativeState.metal4PipelineLogged {\n                    NativeState.metal4PipelineLogged = true\n                    NSLog(\"[metallum] Metal 4 pipeline path engaged (MTL4Compiler)\")\n                }\n                return retainedPointer(state)",
    "                if !NativeState.metal4PipelineLogged {\n                    NativeState.metal4PipelineLogged = true\n                    NSLog(\"[metallum] Metal 4 pipeline path engaged (MTL4Compiler)\")\n                }\n                residencyTrackCreated(state)\n                return retainedPointer(state)",
    "Metal 4 render PSO residency",
)
text = once(
    text,
    "        let state = try device.makeRenderPipelineState(descriptor: descriptor)\n        // Harvest for the next launch; failure only means this PSO is",
    "        let state = try device.makeRenderPipelineState(descriptor: descriptor)\n        residencyTrackCreated(state)\n        // Harvest for the next launch; failure only means this PSO is",
    "Metal 3 fallback render PSO residency",
)

# The exported compute-PSO factory is the generic Java->native path. Track it
# for the same reason as render PSOs; MTLComputePipelineState is MTLAllocation.
text = once(
    text,
    "        do {\n            return retainedPointer(try device.makeComputePipelineState(function: function))\n        } catch {\n            NSLog(\"[metallum] Failed to create compute pipeline state: %@\", String(describing: error))",
    "        do {\n            let state = try device.makeComputePipelineState(function: function)\n            residencyTrackCreated(state)\n            return retainedPointer(state)\n        } catch {\n            NSLog(\"[metallum] Failed to create compute pipeline state: %@\", String(describing: error))",
    "generic compute PSO residency",
)

path.write_text(text)

contract = Path("src/test/java/com/metallum/client/metal/render/MetalResidencyAllocationContractTest.java")
contract.write_text(r'''package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalResidencyAllocationContractTest {
    @Test
    void residencyAuthorityTracksMetalAllocationsIncludingPipelineStates() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(source.contains("private func residencyAdd(_ allocation: any MTLAllocation)"));
        assertTrue(source.contains("private func residencyRemove(_ allocation: any MTLAllocation)"));
        assertTrue(source.contains("private func residencyTrackCreated(_ object: AnyObject?)"));
        assertTrue(source.contains("object as? any MTLAllocation"));
        assertTrue(source.contains("as? any MTLAllocation"));
        assertTrue(source.contains("residencyTrackCreated(state)\n                return retainedPointer(state)"));
        assertTrue(source.contains("let state = try device.makeComputePipelineState(function: function)\n            residencyTrackCreated(state)"));

        // Memoryless textures have tile-only storage and must remain excluded.
        assertTrue(source.contains("texture.storageMode == .memoryless"));
        assertFalse(source.contains("private func residencyAdd(_ resource: MTLResource)"));
    }
}
''')

print("MTLAllocation residency repair staged")
