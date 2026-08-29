from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

native_path = Path('src/main/native/MetallumNative.swift')
native = native_path.read_text()
native = replace_once(
    native,
    '    static var terrainGpuEncodeEnabled = false\n',
    '    static var terrainGpuEncodeEnabled = false\n'
    '    // The explicit diagnostic probe needs stable prefix/scatter output. The\n'
    '    // shipping visible-ICB lane consumes only the visibility bitset and can\n'
    '    // skip every compaction dispatch and candidate-sized scratch buffer.\n'
    '    static var terrainVisibilityCompactionEnabled = true\n',
    'native state flag'
)
native = replace_once(
    native,
    '@_cdecl("metallum_set_terrain_gpu_encode_enabled")\npublic func metallum_set_terrain_gpu_encode_enabled(_ enabled: Int32) {\n    NativeState.terrainGpuEncodeEnabled = enabled != 0\n}\n',
    '@_cdecl("metallum_set_terrain_gpu_encode_enabled")\npublic func metallum_set_terrain_gpu_encode_enabled(_ enabled: Int32) {\n    NativeState.terrainGpuEncodeEnabled = enabled != 0\n}\n\n'
    '@_cdecl("metallum_set_terrain_visibility_compaction_enabled")\n'
    'public func metallum_set_terrain_visibility_compaction_enabled(_ enabled: Int32) {\n'
    '    NativeState.terrainVisibilityCompactionEnabled = enabled != 0\n'
    '}\n',
    'native setter'
)
native = replace_once(
    native,
    '    let epoch: UInt64\n    let candidateCount: Int\n',
    '    let epoch: UInt64\n    let candidateCount: Int\n    let compactionEnabled: Bool\n',
    'owner compaction field'
)
native = replace_once(
    native,
    '        epoch: UInt64,\n        candidateCount: Int,\n        wordCount: Int,\n',
    '        epoch: UInt64,\n        candidateCount: Int,\n        compactionEnabled: Bool,\n        wordCount: Int,\n',
    'owner init arg'
)
native = replace_once(
    native,
    '        self.epoch = epoch\n        self.candidateCount = candidateCount\n        self.wordCount = wordCount\n',
    '        self.epoch = epoch\n        self.candidateCount = candidateCount\n        self.compactionEnabled = compactionEnabled\n        self.wordCount = wordCount\n',
    'owner init assignment'
)
native = replace_once(
    native,
    '        guard isCompleted else { return 0 }\n        guard isSucceeded, wordCapacity >= Int32(wordCount),\n',
    '        guard isCompleted else { return 0 }\n        guard compactionEnabled else { return -1 }\n        guard isSucceeded, wordCapacity >= Int32(wordCount),\n',
    'poll compaction guard'
)
native = replace_once(
    native,
    '    let wordCount = (count + 31) / 32\n    let blockWidth = 256\n',
    '    let wordCount = (count + 31) / 32\n    let compact = NativeState.terrainVisibilityCompactionEnabled\n    let blockWidth = 256\n',
    'probe mode capture'
)
# Shrink compaction-only buffers to one UInt32 when the shipping lane only needs the bitset.
for old, new, label in [
    ('length: count * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let blockSumsBuffer',
     'length: (compact ? count : 1) * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let blockSumsBuffer', 'prefix allocation'),
    ('length: blockCount * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let blockOffsetsBuffer',
     'length: (compact ? blockCount : 1) * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let blockOffsetsBuffer', 'block sums allocation'),
    ('length: blockCount * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let groupSumsBuffer',
     'length: (compact ? blockCount : 1) * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let groupSumsBuffer', 'block offsets allocation'),
    ('length: groupCount * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let groupOffsetsBuffer',
     'length: (compact ? groupCount : 1) * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let groupOffsetsBuffer', 'group sums allocation'),
    ('length: groupCount * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let compactedIndicesBuffer',
     'length: (compact ? groupCount : 1) * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let compactedIndicesBuffer', 'group offsets allocation'),
    ('length: count * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let compactedCountBuffer',
     'length: (compact ? count : 1) * MemoryLayout<UInt32>.stride,\n        options: .storageModeShared\n    ), let compactedCountBuffer', 'compacted allocation'),
]:
    native = replace_once(native, old, new, label)

for old, new, label in [
    ('prefixLocalBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: count)',
     'prefixLocalBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: compact ? count : 1)', 'prefix init'),
    ('blockSumsBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: blockCount)',
     'blockSumsBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: compact ? blockCount : 1)', 'block sums init'),
    ('blockOffsetsBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: blockCount)',
     'blockOffsetsBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: compact ? blockCount : 1)', 'block offsets init'),
    ('groupSumsBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: groupCount)',
     'groupSumsBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: compact ? groupCount : 1)', 'group sums init'),
    ('groupOffsetsBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: groupCount)',
     'groupOffsetsBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: compact ? groupCount : 1)', 'group offsets init'),
    ('compactedIndicesBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: count)',
     'compactedIndicesBuffer.contents().assumingMemoryBound(to: UInt32.self)\n        .initialize(repeating: 0, count: compact ? count : 1)', 'compacted init'),
]:
    native = replace_once(native, old, new, label)

# Keep the visibility dispatch identical, but make all prefix/scatter work conditional.
scan_start = '    computeEncoder.barrier(\n        afterEncoderStages: .dispatch,\n        beforeEncoderStages: .dispatch,\n        visibilityOptions: .device\n    )\n    computeEncoder.setComputePipelineState(pipeline.blockScan)'
if scan_start not in native:
    raise SystemExit('scan start anchor missing')
native = native.replace(scan_start, '    if compact {\n' + scan_start.replace('\n', '\n    '), 1)
terminal = '    // The visibility/compaction results can feed either raster stages or a\n'
if terminal not in native:
    raise SystemExit('terminal queue barrier comment missing')
native = native.replace(terminal, '    }\n' + terminal, 1)

native = replace_once(
    native,
    '        epoch: epoch,\n        candidateCount: count,\n        wordCount: wordCount,\n',
    '        epoch: epoch,\n        candidateCount: count,\n        compactionEnabled: compact,\n        wordCount: wordCount,\n',
    'owner construction mode'
)
native_path.write_text(native)

bridge_path = Path('src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java')
bridge = bridge_path.read_text()
bridge = replace_once(
    bridge,
    '    private static final MethodHandle setTerrainGpuEncodeEnabled;\n',
    '    private static final MethodHandle setTerrainGpuEncodeEnabled;\n'
    '    @Nullable\n    private static final MethodHandle setTerrainVisibilityCompactionEnabled;\n',
    'bridge handle declaration'
)
bridge = replace_once(
    bridge,
    '            setTerrainGpuEncodeEnabled = optionalDowncall(lookup, "metallum_set_terrain_gpu_encode_enabled", FunctionDescriptor.ofVoid(INT));\n',
    '            setTerrainGpuEncodeEnabled = optionalDowncall(lookup, "metallum_set_terrain_gpu_encode_enabled", FunctionDescriptor.ofVoid(INT));\n'
    '            setTerrainVisibilityCompactionEnabled = optionalDowncall(\n'
    '                    lookup, "metallum_set_terrain_visibility_compaction_enabled", FunctionDescriptor.ofVoid(INT)\n'
    '            );\n',
    'bridge setter lookup'
)
bridge = replace_once(
    bridge,
    '    public static void metallum_set_terrain_gpu_encode_enabled(final int enabled) {\n        if (setTerrainGpuEncodeEnabled == null) {\n            return;\n        }\n        try {\n            setTerrainGpuEncodeEnabled.invokeExact(enabled);\n        } catch (Throwable throwable) {\n            throw bridgeFailure("metallum_set_terrain_gpu_encode_enabled", throwable);\n        }\n    }\n',
    '    public static void metallum_set_terrain_gpu_encode_enabled(final int enabled) {\n        if (setTerrainGpuEncodeEnabled == null) {\n            return;\n        }\n        try {\n            setTerrainGpuEncodeEnabled.invokeExact(enabled);\n        } catch (Throwable throwable) {\n            throw bridgeFailure("metallum_set_terrain_gpu_encode_enabled", throwable);\n        }\n    }\n\n'
    '    public static void metallum_set_terrain_visibility_compaction_enabled(final boolean enabled) {\n'
    '        if (setTerrainVisibilityCompactionEnabled == null) {\n            return;\n        }\n'
    '        try {\n            setTerrainVisibilityCompactionEnabled.invokeExact(enabled ? 1 : 0);\n'
    '        } catch (Throwable throwable) {\n'
    '            throw bridgeFailure("metallum_set_terrain_visibility_compaction_enabled", throwable);\n'
    '        }\n    }\n',
    'bridge setter wrapper'
)
bridge_path.write_text(bridge)

# Device chooses compaction only for the explicit diagnostic/oracle lane.
device_path = Path('src/main/java/com/metallum/client/metal/render/MetalDevice.java')
device = device_path.read_text()
device = replace_once(
    device,
    '        MetalNativeBridge.metallum_set_terrain_gpu_encode_enabled(\n                (TerrainSceneSnapshot.GPU_ICB_ENABLED || VISIBLE_GPU_ICB_METAL4)\n                        && metal4Compiler ? 1 : 0\n        );\n',
    '        MetalNativeBridge.metallum_set_terrain_gpu_encode_enabled(\n                (TerrainSceneSnapshot.GPU_ICB_ENABLED || VISIBLE_GPU_ICB_METAL4)\n                        && metal4Compiler ? 1 : 0\n        );\n'
    '        MetalNativeBridge.metallum_set_terrain_visibility_compaction_enabled(\n'
    '                TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED\n'
    '        );\n',
    'device compaction mode'
)
device_path.write_text(device)

probe_path = Path('src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java')
probe = probe_path.read_text()
probe = replace_once(
    probe,
    '                allocationBytes = pendingAllocationBytes(count);\n',
    '                allocationBytes = pendingAllocationBytes(count, ORACLE_ENABLED);\n',
    'pending budget mode'
)
probe = probe.replace('compactionFallbackCount++;', 'if (ORACLE_ENABLED) { compactionFallbackCount++; }')
probe = probe.replace('compactionDispatchCount++;', 'if (ORACLE_ENABLED) { compactionDispatchCount++; }')
probe = probe.replace('compactionProducedCount++;', 'if (ORACLE_ENABLED) { compactionProducedCount++; }')
probe = probe.replace(
    'pendingAllocationBytes(pending.candidateCount())',
    'pendingAllocationBytes(pending.candidateCount(), pending.oracleEnabled())'
)
old_budget = '''    private static long pendingAllocationBytes(final int candidateCount) {\n        int words = TerrainCandidateSnapshot.gpuVisibilityWordCount(candidateCount);\n        int blocks = (candidateCount + 255) >>> 8;\n        int groups = (blocks + 255) >>> 8;\n        long bytes = TerrainCandidateSnapshot.gpuVisibilityCandidateBytes(candidateCount);\n        bytes = Math.addExact(bytes, TerrainCandidateSnapshot.GPU_VISIBILITY_MATRIX_BYTES);\n        bytes = Math.addExact(bytes, Math.multiplyExact((long) words, Integer.BYTES));\n        bytes = Math.addExact(bytes, 2L * Integer.BYTES);\n        bytes = Math.addExact(bytes, Math.multiplyExact((long) candidateCount, Integer.BYTES));\n        bytes = Math.addExact(bytes, Math.multiplyExact((long) blocks, 2L * Integer.BYTES));\n        bytes = Math.addExact(bytes, Math.multiplyExact((long) groups, 2L * Integer.BYTES));\n        bytes = Math.addExact(bytes, Math.multiplyExact((long) candidateCount, Integer.BYTES));\n        bytes = Math.addExact(bytes, Integer.BYTES);\n        return Math.addExact(bytes, 3L * Integer.BYTES);\n    }\n'''
new_budget = '''    private static long pendingAllocationBytes(final int candidateCount) {\n        return pendingAllocationBytes(candidateCount, true);\n    }\n\n    private static long pendingAllocationBytes(final int candidateCount, final boolean compact) {\n        int words = TerrainCandidateSnapshot.gpuVisibilityWordCount(candidateCount);\n        int blocks = (candidateCount + 255) >>> 8;\n        int groups = (blocks + 255) >>> 8;\n        long bytes = TerrainCandidateSnapshot.gpuVisibilityCandidateBytes(candidateCount);\n        bytes = Math.addExact(bytes, TerrainCandidateSnapshot.GPU_VISIBILITY_MATRIX_BYTES);\n        bytes = Math.addExact(bytes, Math.multiplyExact((long) words, Integer.BYTES));\n        bytes = Math.addExact(bytes, 2L * Integer.BYTES);\n        if (compact) {\n            bytes = Math.addExact(bytes, Math.multiplyExact((long) candidateCount, Integer.BYTES));\n            bytes = Math.addExact(bytes, Math.multiplyExact((long) blocks, 2L * Integer.BYTES));\n            bytes = Math.addExact(bytes, Math.multiplyExact((long) groups, 2L * Integer.BYTES));\n            bytes = Math.addExact(bytes, Math.multiplyExact((long) candidateCount, Integer.BYTES));\n        } else {\n            // Six compaction-only scratch/output buffers remain as one-word\n            // placeholders so the stable argument-table layout stays unchanged.\n            bytes = Math.addExact(bytes, 6L * Integer.BYTES);\n        }\n        bytes = Math.addExact(bytes, Integer.BYTES);\n        return Math.addExact(bytes, 3L * Integer.BYTES);\n    }\n'''
probe = replace_once(probe, old_budget, new_budget, 'pending budget implementation')
probe_path.write_text(probe)

test_path = Path('src/test/java/com/metallum/client/metal/render/Metal4TerrainVisibleIcbContractTest.java')
test = test_path.read_text()
insert = '''\n    @Test\n    void visibleOnlyProbeSkipsStableCompactionWork() throws IOException {\n        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));\n        assertTrue(nativeSource.contains("static var terrainVisibilityCompactionEnabled = true"));\n        assertTrue(nativeSource.contains("let compact = NativeState.terrainVisibilityCompactionEnabled"));\n        assertTrue(nativeSource.contains("if compact {"));\n        assertTrue(nativeSource.contains("compactionEnabled: compact"));\n\n        String deviceSource = Files.readString(Path.of("src/main/java/com/metallum/client/metal/render/MetalDevice.java"));\n        assertTrue(deviceSource.contains("metallum_set_terrain_visibility_compaction_enabled"));\n        assertTrue(deviceSource.contains("TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED"));\n\n        String probeSource = Files.readString(Path.of("src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java"));\n        assertTrue(probeSource.contains("pendingAllocationBytes(count, ORACLE_ENABLED)"));\n        assertTrue(probeSource.contains("if (ORACLE_ENABLED) { compactionDispatchCount++; }"));\n    }\n'''
pos = test.rfind('\n}')
if pos < 0:
    raise SystemExit('test class close missing')
test = test[:pos] + insert + test[pos:]
test_path.write_text(test)

print('bitset-only terrain visibility patch applied')
