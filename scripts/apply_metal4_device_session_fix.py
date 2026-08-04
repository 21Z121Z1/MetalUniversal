from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise RuntimeError(f"{label} anchor not found")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        if replacement in text:
            return text
        raise RuntimeError(f"{label} anchor not found")
    return updated


native_path = Path("src/main/native/MetallumNative.swift")
native = native_path.read_text()

native = regex_once(
    native,
    r"(?P<i>\s*)if let existing = metal4CompilerStorage as\? MTL4Compiler \{ return existing \}\n(?P=i)let descriptor = MTL4CompilerDescriptor\(\)",
    """\g<i>if let existing = metal4CompilerStorage as? MTL4Compiler {
\g<i>    if objectAddress(existing.device) == objectAddress(device) {
\g<i>        return existing
\g<i>    }
\g<i>    metal4CompilerStorage = nil
\g<i>    metal4Serializer = nil
\g<i>    metal4LookupArchive = nil
\g<i>}
\g<i>let descriptor = MTL4CompilerDescriptor()""",
    "compiler ownership",
)

native = replace_once(
    native,
    """private final class Metal4MainQueuePilot {
    private static let validationByteCount = 256
""",
    """private final class Metal4MainQueuePilot {
    private static let validationByteCount = 256

    private let device: MTLDevice
""",
    "pilot device field",
)
native = replace_once(
    native,
    """        self.queue = queue
        self.slots = slots
        self.sourceBuffer = sourceBuffer
""",
    """        self.device = device
        self.queue = queue
        self.slots = slots
        self.sourceBuffer = sourceBuffer
""",
    "pilot device initialization",
)
native = replace_once(
    native,
    """    func submitAndWait() -> Bool {
        let slot = slots[nextSlot]
""",
    """    func owns(_ candidate: MTLDevice) -> Bool {
        objectAddress(device) == objectAddress(candidate)
    }

    func submitAndWait() -> Bool {
        let slot = slots[nextSlot]
""",
    "pilot ownership method",
)
native = replace_once(
    native,
    """    func commandBuffer(at index: Int) -> MTL4CommandBuffer { slots[index].commandBuffer }

    func argumentTables(at index: Int) -> (MTL4ArgumentTable, MTL4ArgumentTable) {
""",
    """    func owns(_ candidate: MTLDevice) -> Bool {
        objectAddress(device) == objectAddress(candidate)
    }

    func commandBuffer(at index: Int) -> MTL4CommandBuffer { slots[index].commandBuffer }

    func argumentTables(at index: Int) -> (MTL4ArgumentTable, MTL4ArgumentTable) {
""",
    "main queue ownership method",
)
native = replace_once(
    native,
    """    if let existing = NativeState.metal4MainQueuePilotStorage as? Metal4MainQueuePilot {
        pilot = existing
    } else {
        guard let created = Metal4MainQueuePilot(device) else { return 0 }
""",
    """    if let existing = NativeState.metal4MainQueuePilotStorage as? Metal4MainQueuePilot,
       existing.owns(device) {
        pilot = existing
    } else {
        NativeState.metal4MainQueuePilotStorage = nil
        guard let created = Metal4MainQueuePilot(device) else { return 0 }
""",
    "pilot validation ownership",
)
native = replace_once(
    native,
    """    if NativeState.metal4MainQueueStorage is Metal4MainQueueContext {
        return 1
    }
    guard let context = Metal4MainQueueContext(device, layer: layer) else {
""",
    """    if let existing = NativeState.metal4MainQueueStorage as? Metal4MainQueueContext {
        if existing.owns(device) {
            return 1
        }
        NativeState.metal4MainQueueStorage = nil
    }
    guard let context = Metal4MainQueueContext(device, layer: layer) else {
""",
    "main renderer ownership",
)
native = replace_once(
    native,
    """        if NativeState.residencySetStorage != nil { return 1 }
        let descriptor = MTLResidencySetDescriptor()
""",
    """        if let existing = NativeState.residencySetStorage as? MTLResidencySet {
            if objectAddress(existing.device) == objectAddress(device) {
                queue.addResidencySet(existing)
                return 1
            }
            existing.endResidency()
            NativeState.residencySetStorage = nil
            NativeState.residencyDirty = false
            NativeState.residencyRequested = false
        }
        let descriptor = MTLResidencySetDescriptor()
""",
    "residency ownership",
)

release_anchor = """@_cdecl("metallum_release_object")
public func metallum_release_object(_ obj: UnsafeMutableRawPointer?) {
"""
teardown = """private func teardownMetalDeviceSession(_ device: MTLDevice) {
    let address = objectAddress(device)

    #if os(macOS) && canImport(MetalFX)
    metallum_metalfx_shutdown()
    #endif

    if #available(macOS 26.0, iOS 26.0, *) {
        if let context = NativeState.metal4MainQueueStorage as? Metal4MainQueueContext,
           context.owns(device) {
            NativeState.metal4MainQueueStorage = nil
        }
        if let pilot = NativeState.metal4MainQueuePilotStorage as? Metal4MainQueuePilot,
           pilot.owns(device) {
            NativeState.metal4MainQueuePilotStorage = nil
        }
        NativeState.metal4CompilerLock.lock()
        if let compiler = NativeState.metal4CompilerStorage as? MTL4Compiler,
           objectAddress(compiler.device) == address {
            NativeState.metal4CompilerStorage = nil
            NativeState.metal4Serializer = nil
            NativeState.metal4LookupArchive = nil
        }
        NativeState.metal4CompilerLock.unlock()
    }

    if #available(macOS 15.0, iOS 18.0, *) {
        NativeState.residencyLock.lock()
        if let set = NativeState.residencySetStorage as? MTLResidencySet,
           objectAddress(set.device) == address {
            set.endResidency()
            NativeState.residencySetStorage = nil
            NativeState.residencyDirty = false
            NativeState.residencyRequested = false
        }
        NativeState.residencyLock.unlock()
    }

    NativeState.depthStencilStates.removeAll()
    NativeState.samplerStates.removeAll()
    NativeState.clearPipelines.removeAll()
    NativeState.copyPipelines.removeAll()
    NativeState.presentPipeline = nil
    NativeState.presentLinearSampler = nil
    NativeState.presentNearestSampler = nil
    NativeState.functionLibraries.removeAllObjects()
    NativeState.binaryArchiveLock.lock()
    NativeState.binaryArchive = nil
    NativeState.binaryArchiveReadOnly = false
    NativeState.binaryArchiveLock.unlock()

    NativeState.metal4GenericComputeEncodeCount = 0
    NativeState.metal4GenericBlitEncodeCount = 0
    NativeState.metal4GenericRenderEncodeCount = 0
    NativeState.metal3GenericComputeEncodeCount = 0
    NativeState.metal3GenericBlitEncodeCount = 0
    NativeState.metal4LegacyEncoderViolationCount = 0
    NativeState.metal4AuxiliaryComputeEncodeCount = 0
    NativeState.metal4SpatialEncodeCount = 0
    NativeState.metal4TemporalEncodeCount = 0
    NativeState.metal4FrameGenerationInputCount = 0
    NativeState.metal4PipelineLogged = false
    NativeState.metal4PipelineFallbackLogged = false
    NativeState.metal4CompilerEnabled = false
    NativeState.metal4PresentEnabled = false
}

@_cdecl("metallum_metal_device_shutdown")
public func metallum_metal_device_shutdown(_ device: MTLDevice) {
    autoreleasepool {
        teardownMetalDeviceSession(device)
    }
}

@_cdecl("metallum_release_object")
public func metallum_release_object(_ obj: UnsafeMutableRawPointer?) {
"""
native = replace_once(native, release_anchor, teardown, "device teardown export")
native_path.write_text(native)

bridge_path = Path("src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java")
bridge = bridge_path.read_text()
bridge = replace_once(
    bridge,
    '            metal4Supported = downcall(lookup, "metallum_metal4_supported", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));\n            metal4MainQueuePilotValidate = downcall',
    '            metal4Supported = downcall(lookup, "metallum_metal4_supported", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));\n            metalDeviceShutdown = downcall(lookup, "metallum_metal_device_shutdown", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));\n            metal4MainQueuePilotValidate = downcall',
    "bridge downcall",
)
bridge = replace_once(
    bridge,
    """    private static final MethodHandle metal4Supported;
    private static final MethodHandle metal4MainQueuePilotValidate;
""",
    """    private static final MethodHandle metal4Supported;
    private static final MethodHandle metalDeviceShutdown;
    private static final MethodHandle metal4MainQueuePilotValidate;
""",
    "bridge field",
)
bridge = replace_once(
    bridge,
    """    public static int metallum_metal4_main_renderer_enable(
            final MemorySegment device,
""",
    """    public static void metallum_metal_device_shutdown(final MemorySegment device) {
        try {
            metalDeviceShutdown.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metal_device_shutdown", throwable);
        }
    }

    public static int metallum_metal4_main_renderer_enable(
            final MemorySegment device,
""",
    "bridge wrapper",
)
bridge_path.write_text(bridge)

fx_path = Path("src/main/java/com/metallum/client/metal/render/MetalFxManager.java")
fx = fx_path.read_text()
fx = replace_once(
    fx,
    """    public static void close() {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.closeInternal();
            active = null;
        }
    }
""",
    """    public static synchronized void close() {
        MetalFxManager manager = active;
        if (manager != null) {
            manager.closeInternal();
            active = null;
        }
    }

    static synchronized void close(final MetalDevice device) {
        MetalFxManager manager = active;
        if (manager != null && manager.device == device) {
            manager.closeInternal();
            active = null;
        }
    }
""",
    "MetalFX close ownership",
)
fx_path.write_text(fx)

device_path = Path("src/main/java/com/metallum/client/metal/render/MetalDevice.java")
device = device_path.read_text()
device = replace_once(
    device,
    """        if (current == this) {
            current = null;
        }

        // Freeze and join background compilation""",
    """        if (current == this) {
            current = null;
        }

        MetalFxManager.close(this);

        // Freeze and join background compilation""",
    "MetalDevice MetalFX close",
)
device = replace_once(
    device,
    """        this.commandQueue.close();
        MetalNativeBridge.metallum_release_object(this.metalDeviceHandle);""",
    """        MetalNativeBridge.metallum_metal_device_shutdown(this.metalDeviceHandle);
        this.commandQueue.close();
        MetalNativeBridge.metallum_release_object(this.metalDeviceHandle);""",
    "MetalDevice native shutdown",
)
device_path.write_text(device)
