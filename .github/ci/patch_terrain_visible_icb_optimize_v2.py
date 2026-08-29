from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

native_path = Path("src/main/native/MetallumNative.swift")
native = native_path.read_text()
native = once(
    native,
    "    static var terrainGpuEncodeEnabled = false\n",
    "    static var terrainGpuEncodeEnabled = false\n"
    "    // Optional optimization for sparse GPU-authored visible ICBs. Keep it\n"
    "    // independently reversible until physical-GPU A/B establishes benefit.\n"
    "    static var terrainVisibleIcbOptimizeEnabled = false\n",
    "native optimize state",
)
native = once(
    native,
    '@_cdecl("metallum_set_terrain_gpu_encode_enabled")\npublic func metallum_set_terrain_gpu_encode_enabled(_ enabled: Int32) {\n    NativeState.terrainGpuEncodeEnabled = enabled != 0\n}\n',
    '@_cdecl("metallum_set_terrain_gpu_encode_enabled")\npublic func metallum_set_terrain_gpu_encode_enabled(_ enabled: Int32) {\n    NativeState.terrainGpuEncodeEnabled = enabled != 0\n}\n\n'
    '@_cdecl("metallum_set_terrain_visible_icb_optimize_enabled")\n'
    'public func metallum_set_terrain_visible_icb_optimize_enabled(_ enabled: Int32) {\n'
    '    NativeState.terrainVisibleIcbOptimizeEnabled = enabled != 0\n'
    '}\n',
    "native optimize setter",
)

# Scope the insertion to the sparse-visible authoring export. There are two
# GPU ICB producers with the same producer barrier; only this one has blank
# reset slots and should be optimized.
entry = native.index('@_cdecl("metallum_MTLDevice_createTerrainVisibleGpuIndexedIcb")')
end = native.find('\n@_cdecl(', entry + 1)
if end < 0:
    raise SystemExit("visible ICB export end not found")
visible = native[entry:end]
barrier = '''    computeEncoder.barrier(
        afterStages: .dispatch,
        beforeQueueStages: [.vertex, .fragment],
        visibilityOptions: .device
    )
'''
if visible.count(barrier) != 1:
    raise SystemExit(f"visible producer barrier: expected one match, got {visible.count(barrier)}")
visible = visible.replace(
    barrier,
    '''    if NativeState.terrainVisibleIcbOptimizeEnabled {
        computeEncoder.optimizeCommands(buffer: commandBuffer, range: 0..<commandCount)
    }
''' + barrier,
    1,
)
native = native[:entry] + visible + native[end:]
native_path.write_text(native)

bridge_path = Path("src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java")
bridge = bridge_path.read_text()
bridge = once(
    bridge,
    "    private static final MethodHandle setTerrainGpuEncodeEnabled;\n",
    "    private static final MethodHandle setTerrainGpuEncodeEnabled;\n"
    "    @Nullable\n    private static final MethodHandle setTerrainVisibleIcbOptimizeEnabled;\n",
    "bridge handle declaration",
)
lookup = '''            setTerrainGpuEncodeEnabled = optionalDowncall(
                    lookup,
                    "metallum_set_terrain_gpu_encode_enabled",
                    FunctionDescriptor.ofVoid(INT)
            );
'''
bridge = once(
    bridge,
    lookup,
    lookup + '''            setTerrainVisibleIcbOptimizeEnabled = optionalDowncall(
                    lookup,
                    "metallum_set_terrain_visible_icb_optimize_enabled",
                    FunctionDescriptor.ofVoid(INT)
            );
''',
    "bridge optimize lookup",
)
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
bridge = once(
    bridge,
    wrapper,
    wrapper + '''
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
''',
    "bridge optimize wrapper",
)
bridge_path.write_text(bridge)

device_path = Path("src/main/java/com/metallum/client/metal/render/MetalDevice.java")
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
    "device optimize property",
)
setter = '''        MetalNativeBridge.metallum_set_terrain_gpu_encode_enabled(
                (TerrainSceneSnapshot.GPU_ICB_ENABLED || VISIBLE_GPU_ICB_METAL4)
                        && metal4Compiler ? 1 : 0
        );
'''
device = once(
    device,
    setter,
    setter + '''        MetalNativeBridge.metallum_set_terrain_visible_icb_optimize_enabled(
                VISIBLE_GPU_ICB_METAL4 && VISIBLE_GPU_ICB_OPTIMIZE && metal4Compiler
        );
''',
    "device optimize setter",
)
device_path.write_text(device)

test_path = Path("src/test/java/com/metallum/client/metal/render/Metal4TerrainVisibleIcbContractTest.java")
test = test_path.read_text()
insert = '''
    @Test
    void optimizedVisibleIcbUsesTheExactResetAndExecuteRange() throws IOException {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int entry = nativeSource.indexOf("metallum_MTLDevice_createTerrainVisibleGpuIndexedIcb");
        int execute = nativeSource.indexOf("metallum_MTLRenderCommandEncoder_executeTerrainIcb", entry);
        assertTrue(entry > 0 && execute > entry);
        String visible = nativeSource.substring(entry, execute);
        assertTrue(visible.contains("resetCommands(buffer: commandBuffer, range: 0..<commandCount)"));
        assertTrue(visible.contains("optimizeCommands(buffer: commandBuffer, range: 0..<commandCount)"));
        assertTrue(visible.indexOf("optimizeCommands") < visible.indexOf("beforeQueueStages: [.vertex, .fragment]"));

        String deviceSource = Files.readString(Path.of("src/main/java/com/metallum/client/metal/render/MetalDevice.java"));
        assertTrue(deviceSource.contains("metallum.opt.terrainVisibleIcbOptimize"));
        assertTrue(deviceSource.contains("VISIBLE_GPU_ICB_METAL4 && VISIBLE_GPU_ICB_OPTIMIZE && metal4Compiler"));
    }
'''
pos = test.rfind("\n}")
if pos < 0:
    raise SystemExit("contract test close missing")
test_path.write_text(test[:pos] + insert + test[pos:])
print("visible ICB optimize v2 patch applied")
