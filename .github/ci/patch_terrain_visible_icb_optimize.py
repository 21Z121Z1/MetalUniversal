from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one match, got {count}')
    return text.replace(old, new, 1)

native_path = Path('src/main/native/MetallumNative.swift')
native = native_path.read_text()
native = once(
    native,
    '    static var terrainGpuEncodeEnabled = false\n',
    '    static var terrainGpuEncodeEnabled = false\n'
    '    // Optional sparse-ICB compaction. Apple recommends optimizing GPU-authored\n'
    '    // ICBs with blank commands, but the optimized range has strict execution\n'
    '    // semantics, so keep this independently reversible until hardware A/B.\n'
    '    static var terrainVisibleIcbOptimizeEnabled = false\n',
    'native optimize state'
)
native = once(
    native,
    '@_cdecl("metallum_set_terrain_gpu_encode_enabled")\npublic func metallum_set_terrain_gpu_encode_enabled(_ enabled: Int32) {\n    NativeState.terrainGpuEncodeEnabled = enabled != 0\n}\n',
    '@_cdecl("metallum_set_terrain_gpu_encode_enabled")\npublic func metallum_set_terrain_gpu_encode_enabled(_ enabled: Int32) {\n    NativeState.terrainGpuEncodeEnabled = enabled != 0\n}\n\n'
    '@_cdecl("metallum_set_terrain_visible_icb_optimize_enabled")\n'
    'public func metallum_set_terrain_visible_icb_optimize_enabled(_ enabled: Int32) {\n'
    '    NativeState.terrainVisibleIcbOptimizeEnabled = enabled != 0\n'
    '}\n',
    'native optimize setter'
)
# Only the sparse visible authoring path gets optimized. The range is exactly
# the same full source range that resetCommands and executeTerrainIcb use.
needle = '''    computeEncoder.dispatchThreads(
        threadsPerGrid: MTLSize(width: commandCount, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(width: threadWidth, height: 1, depth: 1)
    )
    computeEncoder.barrier(
        afterStages: .dispatch,
        beforeQueueStages: [.vertex, .fragment],
        visibilityOptions: .device
    )
'''
replacement = '''    computeEncoder.dispatchThreads(
        threadsPerGrid: MTLSize(width: commandCount, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(width: threadWidth, height: 1, depth: 1)
    )
    if NativeState.terrainVisibleIcbOptimizeEnabled {
        computeEncoder.optimizeCommands(buffer: commandBuffer, range: 0..<commandCount)
    }
    computeEncoder.barrier(
        afterStages: .dispatch,
        beforeQueueStages: [.vertex, .fragment],
        visibilityOptions: .device
    )
'''
if native.count(needle) != 1:
    raise SystemExit(f'visible dispatch anchor: expected one match, got {native.count(needle)}')
native_path.write_text(native.replace(needle, replacement, 1))

bridge_path = Path('src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java')
bridge = bridge_path.read_text()
bridge = once(
    bridge,
    '    private static final MethodHandle setTerrainGpuEncodeEnabled;\n',
    '    private static final MethodHandle setTerrainGpuEncodeEnabled;\n'
    '    @Nullable\n    private static final MethodHandle setTerrainVisibleIcbOptimizeEnabled;\n',
    'bridge handle declaration'
)
lookup = '''            setTerrainGpuEncodeEnabled = optionalDowncall(
                    lookup,
                    "metallum_set_terrain_gpu_encode_enabled",
                    FunctionDescriptor.ofVoid(INT)
            );
'''
lookup_new = lookup + '''            setTerrainVisibleIcbOptimizeEnabled = optionalDowncall(
                    lookup,
                    "metallum_set_terrain_visible_icb_optimize_enabled",
                    FunctionDescriptor.ofVoid(INT)
            );
'''
bridge = once(bridge, lookup, lookup_new, 'bridge optimize lookup')
wrapper = '''    public static void metallum_set_terrain_gpu_encode_enabled(final int enabled) {
        if (setTerrainGpuEncodeEnabled == null) {
            return;
        }
        try {
            setTerrainGpuEncodeEnabled.invokeExact(enabled);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_terrain_gpu_encode_enabled", throwable);
        }
    }
'''
wrapper_new = wrapper + '''
    public static void metallum_set_terrain_visible_icb_optimize_enabled(final boolean enabled) {
        if (setTerrainVisibleIcbOptimizeEnabled == null) {
            return;
        }
        try {
            setTerrainVisibleIcbOptimizeEnabled.invokeExact(enabled ? 1 : 0);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_terrain_visible_icb_optimize_enabled", throwable);
        }
    }
'''
bridge = once(bridge, wrapper, wrapper_new, 'bridge optimize wrapper')
bridge_path.write_text(bridge)

device_path = Path('src/main/java/com/metallum/client/metal/render/MetalDevice.java')
device = device_path.read_text()
anchor = '''    private static final boolean GPU_VISIBILITY_PROBE_METAL4 =
            EXPLICIT_GPU_VISIBILITY_PROBE_METAL4 || VISIBLE_GPU_ICB_METAL4;
'''
device = once(
    device,
    anchor,
    anchor + '''    private static final boolean VISIBLE_GPU_ICB_OPTIMIZE =
            Boolean.getBoolean("metallum.opt.terrainVisibleIcbOptimize");
''',
    'device optimize property'
)
setter_anchor = '''        MetalNativeBridge.metallum_set_terrain_gpu_encode_enabled(
                (TerrainSceneSnapshot.GPU_ICB_ENABLED || VISIBLE_GPU_ICB_METAL4)
                        && metal4Compiler ? 1 : 0
        );
'''
device = once(
    device,
    setter_anchor,
    setter_anchor + '''        MetalNativeBridge.metallum_set_terrain_visible_icb_optimize_enabled(
                VISIBLE_GPU_ICB_METAL4 && VISIBLE_GPU_ICB_OPTIMIZE && metal4Compiler
        );
''',
    'device optimize setter'
)
device_path.write_text(device)

test_path = Path('src/test/java/com/metallum/client/metal/render/Metal4TerrainVisibleIcbContractTest.java')
test = test_path.read_text()
insert = '''
    @Test
    void optimizedVisibleIcbUsesTheExactExecutedSourceRange() throws IOException {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int entry = nativeSource.indexOf("metallum_MTLDevice_createTerrainVisibleGpuIndexedIcb");
        int execute = nativeSource.indexOf("metallum_MTLRenderCommandEncoder_executeTerrainIcb", entry);
        assertTrue(entry > 0 && execute > entry);
        String visible = nativeSource.substring(entry, execute);
        assertTrue(visible.contains("resetCommands(buffer: commandBuffer, range: 0..<commandCount)"));
        assertTrue(visible.contains("optimizeCommands(buffer: commandBuffer, range: 0..<commandCount)"));

        String deviceSource = Files.readString(Path.of("src/main/java/com/metallum/client/metal/render/MetalDevice.java"));
        assertTrue(deviceSource.contains("metallum.opt.terrainVisibleIcbOptimize"));
        assertTrue(deviceSource.contains("VISIBLE_GPU_ICB_METAL4 && VISIBLE_GPU_ICB_OPTIMIZE && metal4Compiler"));
    }
'''
pos = test.rfind('\n}')
if pos < 0:
    raise SystemExit('contract test close missing')
test_path.write_text(test[:pos] + insert + test[pos:])
print('visible ICB optimize patch applied')
