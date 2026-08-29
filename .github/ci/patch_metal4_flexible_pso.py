from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

native_path = Path('src/main/native/MetallumNative.swift')
native = native_path.read_text()

native = replace_once(native,
'''private struct TerrainVisibilityComputePipelineKey: Hashable {
    let deviceAddress: UInt
}
''',
'''private struct TerrainVisibilityComputePipelineKey: Hashable {
    let deviceAddress: UInt
}

/// Common-state identity for a Metal 4 flexible render pipeline. Color attachment
/// format/write/blend state is deliberately absent: those fields are compiled as
/// unspecialized in the base PSO and supplied only to terminal specializations.
private struct Metal4FlexiblePipelineBaseKey: Hashable {
    let deviceAddress: UInt
    let vertexFunctionAddress: UInt
    let fragmentFunctionAddress: UInt
    let rasterSampleCount: Int
    let inputPrimitiveTopology: UInt
    let alphaToCoverage: Bool
    let alphaToOne: Bool
    let rasterizationEnabled: Bool
    let maxVertexAmplificationCount: Int
    let supportIndirectCommandBuffers: Bool
    let vertexDescriptorHash: Int
}
''', 'native base key type')

native = replace_once(native,
'''    static var metal4CompilerEnabled = false
''',
'''    static var metal4CompilerEnabled = false
    // Flexible PSO specialization is independently reversible. The base cache
    // owns only unspecialized Metal 4 PSOs; every returned child is terminal and
    // falls back to the existing direct Metal 4 compile path on any failure.
    static var metal4FlexiblePsoEnabled = false
    static let metal4FlexiblePsoLock = NSLock()
    static var metal4FlexiblePsoBases: [Metal4FlexiblePipelineBaseKey: MTLRenderPipelineState] = [:]
    static var metal4FlexiblePsoLogged = false
    static var metal4FlexiblePsoFallbackLogged = false
''', 'native state')

old_sig = '''private func makeMetal4Descriptor(_ src: MTLRenderPipelineDescriptor) -> MTL4RenderPipelineDescriptor? {'''
new_sig = '''private func makeMetal4Descriptor(
    _ src: MTLRenderPipelineDescriptor,
    flexibleColorState: Bool = false
) -> MTL4RenderPipelineDescriptor? {'''
native = replace_once(native, old_sig, new_sig, 'descriptor signature')

old_color = '''        d.pixelFormat = s.pixelFormat
        d.writeMask = s.writeMask
        d.blendingState = s.isBlendingEnabled ? .enabled : .disabled
        if s.isBlendingEnabled {
            d.sourceRGBBlendFactor = s.sourceRGBBlendFactor
            d.destinationRGBBlendFactor = s.destinationRGBBlendFactor
            d.rgbBlendOperation = s.rgbBlendOperation
            d.sourceAlphaBlendFactor = s.sourceAlphaBlendFactor
            d.destinationAlphaBlendFactor = s.destinationAlphaBlendFactor
            d.alphaBlendOperation = s.alphaBlendOperation
        }
'''
new_color = '''        if flexibleColorState {
            d.pixelFormat = .unspecialized
            d.writeMask = .unspecialized
            d.blendingState = .unspecialized
            d.sourceRGBBlendFactor = .unspecialized
            d.destinationRGBBlendFactor = .unspecialized
            d.rgbBlendOperation = .unspecialized
            d.sourceAlphaBlendFactor = .unspecialized
            d.destinationAlphaBlendFactor = .unspecialized
            d.alphaBlendOperation = .unspecialized
        } else {
            d.pixelFormat = s.pixelFormat
            d.writeMask = s.writeMask
            d.blendingState = s.isBlendingEnabled ? .enabled : .disabled
            if s.isBlendingEnabled {
                d.sourceRGBBlendFactor = s.sourceRGBBlendFactor
                d.destinationRGBBlendFactor = s.destinationRGBBlendFactor
                d.rgbBlendOperation = s.rgbBlendOperation
                d.sourceAlphaBlendFactor = s.sourceAlphaBlendFactor
                d.destinationAlphaBlendFactor = s.destinationAlphaBlendFactor
                d.alphaBlendOperation = s.alphaBlendOperation
            }
        }
'''
native = replace_once(native, old_color, new_color, 'color translation')

marker = '''    return dst
}

/// Opens (or creates) the on-disk PSO binary archive.'''
helpers = '''    return dst
}

private func metal4VertexDescriptorHash(_ descriptor: MTLVertexDescriptor?) -> Int {
    guard let descriptor else { return 0 }
    var hasher = Hasher()
    for index in 0..<31 {
        let attribute = descriptor.attributes[index]
        hasher.combine(attribute.format.rawValue)
        hasher.combine(attribute.offset)
        hasher.combine(attribute.bufferIndex)
        let layout = descriptor.layouts[index]
        hasher.combine(layout.stride)
        hasher.combine(layout.stepFunction.rawValue)
        hasher.combine(layout.stepRate)
    }
    return hasher.finalize()
}

private func metal4FlexibleBaseKey(
    device: MTLDevice,
    descriptor: MTLRenderPipelineDescriptor
) -> Metal4FlexiblePipelineBaseKey? {
    guard let vertex = descriptor.vertexFunction else { return nil }
    return Metal4FlexiblePipelineBaseKey(
        deviceAddress: objectAddress(device),
        vertexFunctionAddress: objectAddress(vertex),
        fragmentFunctionAddress: descriptor.fragmentFunction.map(objectAddress) ?? 0,
        rasterSampleCount: descriptor.rasterSampleCount,
        inputPrimitiveTopology: descriptor.inputPrimitiveTopology.rawValue,
        alphaToCoverage: descriptor.isAlphaToCoverageEnabled,
        alphaToOne: descriptor.isAlphaToOneEnabled,
        rasterizationEnabled: descriptor.isRasterizationEnabled,
        maxVertexAmplificationCount: descriptor.maxVertexAmplificationCount,
        supportIndirectCommandBuffers: descriptor.supportIndirectCommandBuffers,
        vertexDescriptorHash: metal4VertexDescriptorHash(descriptor.vertexDescriptor)
    )
}

@available(macOS 26.0, iOS 26.0, *)
private func applyMetal4ColorSpecialization(
    concrete: MTL4RenderPipelineDescriptor,
    specialization: MTL4RenderPipelineDescriptor
) {
    for index in 0..<8 {
        guard let source = concrete.colorAttachments[index],
              let target = specialization.colorAttachments[index] else { continue }
        target.pixelFormat = source.pixelFormat
        target.writeMask = source.writeMask
        target.blendingState = source.blendingState
        target.sourceRGBBlendFactor = source.sourceRGBBlendFactor
        target.destinationRGBBlendFactor = source.destinationRGBBlendFactor
        target.rgbBlendOperation = source.rgbBlendOperation
        target.sourceAlphaBlendFactor = source.sourceAlphaBlendFactor
        target.destinationAlphaBlendFactor = source.destinationAlphaBlendFactor
        target.alphaBlendOperation = source.alphaBlendOperation
    }
}

@available(macOS 26.0, iOS 26.0, *)
private func makeMetal4FlexibleRenderPipelineState(
    device: MTLDevice,
    sourceDescriptor: MTLRenderPipelineDescriptor,
    concreteDescriptor: MTL4RenderPipelineDescriptor,
    compiler: MTL4Compiler
) throws -> MTLRenderPipelineState? {
    guard NativeState.metal4FlexiblePsoEnabled,
          let key = metal4FlexibleBaseKey(device: device, descriptor: sourceDescriptor) else {
        return nil
    }

    NativeState.metal4FlexiblePsoLock.lock()
    var base = NativeState.metal4FlexiblePsoBases[key]
    NativeState.metal4FlexiblePsoLock.unlock()

    if base == nil {
        guard let unspecialized = makeMetal4Descriptor(sourceDescriptor, flexibleColorState: true) else {
            return nil
        }
        let created: MTLRenderPipelineState
        if let archive = NativeState.metal4LookupArchive as? MTL4Archive {
            let options = MTL4CompilerTaskOptions()
            options.lookupArchives = [archive]
            created = try compiler.makeRenderPipelineState(
                descriptor: unspecialized,
                compilerTaskOptions: options
            )
        } else {
            created = try compiler.makeRenderPipelineState(descriptor: unspecialized)
        }
        NativeState.metal4FlexiblePsoLock.lock()
        if let raced = NativeState.metal4FlexiblePsoBases[key] {
            base = raced
        } else {
            NativeState.metal4FlexiblePsoBases[key] = created
            base = created
        }
        NativeState.metal4FlexiblePsoLock.unlock()
    }

    guard let base,
          let specialization = base.makeRenderPipelineDescriptorForSpecialization()
              as? MTL4RenderPipelineDescriptor else {
        return nil
    }
    applyMetal4ColorSpecialization(
        concrete: concreteDescriptor,
        specialization: specialization
    )
    return try compiler.makeRenderPipelineStateBySpecialization(
        descriptor: specialization,
        pipeline: base
    )
}

/// Opens (or creates) the on-disk PSO binary archive.'''
native = replace_once(native, marker, helpers, 'flexible helpers')

# Insert setter/reset after the existing compiler switch.
native = replace_once(native,
'''@_cdecl("metallum_set_metal4_compiler_enabled")
public func metallum_set_metal4_compiler_enabled(_ enabled: Int32) {
    NativeState.metal4CompilerEnabled = enabled != 0
}
''',
'''@_cdecl("metallum_set_metal4_compiler_enabled")
public func metallum_set_metal4_compiler_enabled(_ enabled: Int32) {
    NativeState.metal4CompilerEnabled = enabled != 0
}

@_cdecl("metallum_set_metal4_flexible_pso_enabled")
public func metallum_set_metal4_flexible_pso_enabled(_ enabled: Int32) {
    NativeState.metal4FlexiblePsoEnabled = enabled != 0
    if enabled == 0 {
        NativeState.metal4FlexiblePsoLock.lock()
        NativeState.metal4FlexiblePsoBases.removeAll(keepingCapacity: false)
        NativeState.metal4FlexiblePsoLock.unlock()
    }
}

@_cdecl("metallum_metal4_flexible_pso_reset")
public func metallum_metal4_flexible_pso_reset() {
    NativeState.metal4FlexiblePsoLock.lock()
    NativeState.metal4FlexiblePsoBases.removeAll(keepingCapacity: true)
    NativeState.metal4FlexiblePsoLock.unlock()
}
''', 'native setter')

# Route direct Metal 4 pipeline creation through specialization first.
old_compile = '''            do {
                // lookupArchives is Metal 4's replacement for
                // descriptor.binaryArchives: last launch's compiled pipelines
                // are found here instead of being recompiled. The serializer
                // attached to the compiler collects this launch's, and
                // metallum_pso_archive_flush writes them back.
                let state: MTLRenderPipelineState
                if let archive = NativeState.metal4LookupArchive as? MTL4Archive {
                    let options = MTL4CompilerTaskOptions()
                    options.lookupArchives = [archive]
                    state = try compiler.makeRenderPipelineState(
                        descriptor: metal4Descriptor,
                        compilerTaskOptions: options
                    )
                } else {
                    state = try compiler.makeRenderPipelineState(descriptor: metal4Descriptor)
                }
'''
new_compile = '''            do {
                // Flexible color state reuses one unspecialized shader/common-state
                // base across terminal attachment/blend specializations. A failure
                // is not renderer failure: fall through to this exact direct Metal 4
                // compile path below.
                if NativeState.metal4FlexiblePsoEnabled {
                    do {
                        if let specialized = try makeMetal4FlexibleRenderPipelineState(
                            device: device,
                            sourceDescriptor: descriptor,
                            concreteDescriptor: metal4Descriptor,
                            compiler: compiler
                        ) {
                            if !NativeState.metal4FlexiblePsoLogged {
                                NativeState.metal4FlexiblePsoLogged = true
                                NSLog("[metallum] Metal 4 flexible PSO specialization engaged")
                            }
                            return retainedPointer(specialized)
                        }
                    } catch {
                        if !NativeState.metal4FlexiblePsoFallbackLogged {
                            NativeState.metal4FlexiblePsoFallbackLogged = true
                            NSLog(
                                "[metallum] Metal 4 flexible PSO specialization failed; using direct compile: %@",
                                String(describing: error)
                            )
                        }
                    }
                }

                // lookupArchives is Metal 4's replacement for
                // descriptor.binaryArchives: last launch's compiled pipelines
                // are found here instead of being recompiled. The serializer
                // attached to the compiler collects this launch's, and
                // metallum_pso_archive_flush writes them back.
                let state: MTLRenderPipelineState
                if let archive = NativeState.metal4LookupArchive as? MTL4Archive {
                    let options = MTL4CompilerTaskOptions()
                    options.lookupArchives = [archive]
                    state = try compiler.makeRenderPipelineState(
                        descriptor: metal4Descriptor,
                        compilerTaskOptions: options
                    )
                } else {
                    state = try compiler.makeRenderPipelineState(descriptor: metal4Descriptor)
                }
'''
native = replace_once(native, old_compile, new_compile, 'compile route')
native_path.write_text(native)

# Java bridge.
bridge_path = Path('src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java')
bridge = bridge_path.read_text()
bridge = replace_once(bridge,
'''            setMetal4CompilerEnabled = downcall(lookup, "metallum_set_metal4_compiler_enabled", FunctionDescriptor.ofVoid(INT));
''',
'''            setMetal4CompilerEnabled = downcall(lookup, "metallum_set_metal4_compiler_enabled", FunctionDescriptor.ofVoid(INT));
            setMetal4FlexiblePsoEnabled = optionalDowncall(
                    lookup, "metallum_set_metal4_flexible_pso_enabled", FunctionDescriptor.ofVoid(INT)
            );
            metal4FlexiblePsoReset = optionalDowncall(
                    lookup, "metallum_metal4_flexible_pso_reset", FunctionDescriptor.ofVoid()
            );
''', 'bridge lookup')
bridge = replace_once(bridge,
'''    private static final MethodHandle setMetal4CompilerEnabled;
''',
'''    private static final MethodHandle setMetal4CompilerEnabled;
    @Nullable
    private static final MethodHandle setMetal4FlexiblePsoEnabled;
    @Nullable
    private static final MethodHandle metal4FlexiblePsoReset;
''', 'bridge fields')

# Add methods immediately before terrain ICB setter method if present, otherwise before a known next method.
anchor = '''    public static void metallum_set_terrain_icb_enabled(final int enabled) {'''
if anchor not in bridge:
    anchor = '''    public static void metallum_set_metal4_present_enabled(final int enabled) {'''
methods = '''    public static void metallum_set_metal4_flexible_pso_enabled(final boolean enabled) {
        if (setMetal4FlexiblePsoEnabled == null) return;
        try {
            setMetal4FlexiblePsoEnabled.invokeExact(enabled ? 1 : 0);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_metal4_flexible_pso_enabled", throwable);
        }
    }

    public static void metallum_metal4_flexible_pso_reset() {
        if (metal4FlexiblePsoReset == null) return;
        try {
            metal4FlexiblePsoReset.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metal4_flexible_pso_reset", throwable);
        }
    }

'''
bridge = replace_once(bridge, anchor, methods + anchor, 'bridge methods')
bridge_path.write_text(bridge)

# MetalDevice gate and cache-generation reset.
device_path = Path('src/main/java/com/metallum/client/metal/render/MetalDevice.java')
device = device_path.read_text()
device = replace_once(device,
'''    private static final boolean METAL4_COMPILER =
                    Boolean.parseBoolean(System.getProperty("metallum.opt.metal4Compiler", "false"))
                    || TerrainSceneSnapshot.ICB_ENABLED
                    || TerrainSceneSnapshot.GPU_ICB_ENABLED
                    || GPU_VISIBILITY_PROBE_METAL4;
''',
'''    private static final boolean METAL4_COMPILER =
                    Boolean.parseBoolean(System.getProperty("metallum.opt.metal4Compiler", "false"))
                    || TerrainSceneSnapshot.ICB_ENABLED
                    || TerrainSceneSnapshot.GPU_ICB_ENABLED
                    || GPU_VISIBILITY_PROBE_METAL4;
    private static final boolean METAL4_FLEXIBLE_PSO =
            Boolean.parseBoolean(System.getProperty("metallum.opt.metal4FlexiblePso", "false"));
''', 'device flag')
device = replace_once(device,
'''        MetalNativeBridge.metallum_set_metal4_compiler_enabled(metal4Compiler ? 1 : 0);
''',
'''        MetalNativeBridge.metallum_set_metal4_compiler_enabled(metal4Compiler ? 1 : 0);
        MetalNativeBridge.metallum_set_metal4_flexible_pso_enabled(
                metal4Compiler && METAL4_FLEXIBLE_PSO
        );
''', 'device init')
device = replace_once(device,
'''            this.compiledPipelines.values().forEach(MetalCompiledRenderPipeline::close);
            this.compiledPipelines.clear();
            this.shaderCache.values().forEach(IntermediaryShaderModule::close);
''',
'''            this.compiledPipelines.values().forEach(MetalCompiledRenderPipeline::close);
            this.compiledPipelines.clear();
            // Unspecialized bases retain shader/common-state identity. Drop them
            // before this generation releases MTLFunction handles so an address
            // cannot be recycled into a stale base-cache key after resource reload.
            MetalNativeBridge.metallum_metal4_flexible_pso_reset();
            this.shaderCache.values().forEach(IntermediaryShaderModule::close);
''', 'device reset')
device_path.write_text(device)
