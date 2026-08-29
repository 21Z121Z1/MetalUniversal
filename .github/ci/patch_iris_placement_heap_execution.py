from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))

# ---------------------------------------------------------------------------
# Java native bridge: optional placement-heap ABI. Older dylibs fail closed.
# ---------------------------------------------------------------------------
bridge = ROOT / "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
replace_once(
    bridge,
    '            createTextureView = downcall(lookup, "metallum_create_texture_view", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));',
    '''            irisPlacementHeapCreate = optionalDowncall(\n                    lookup,\n                    "metallum_iris_placement_heap_create",\n                    FunctionDescriptor.of(\n                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG\n                    )\n            );\n            irisPlacementHeapTexture = optionalDowncall(\n                    lookup,\n                    "metallum_iris_placement_heap_texture",\n                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG)\n            );\n            createTextureView = downcall(lookup, "metallum_create_texture_view", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));'''
)
replace_once(
    bridge,
    '    private static final MethodHandle createTexture;\n    private static final MethodHandle createTextureView;',
    '''    private static final MethodHandle createTexture;\n    @Nullable\n    private static final MethodHandle irisPlacementHeapCreate;\n    @Nullable\n    private static final MethodHandle irisPlacementHeapTexture;\n    private static final MethodHandle createTextureView;'''
)
replace_once(
    bridge,
    '    public static MemorySegment metallum_create_texture_view(final MemorySegment texture, final long baseMipLevel, final long mipLevelCount) {',
    '''    public static boolean irisPlacementHeapAvailable() {\n        return irisPlacementHeapCreate != null && irisPlacementHeapTexture != null;\n    }\n\n    public static MemorySegment irisPlacementHeapCreate(\n            final MemorySegment device,\n            final MemorySegment records,\n            final long recordCount,\n            final long slotCount\n    ) {\n        if (!irisPlacementHeapAvailable() || recordCount <= 0L || slotCount <= 0L) {\n            return MemorySegment.NULL;\n        }\n        try {\n            return (MemorySegment) irisPlacementHeapCreate.invokeExact(\n                    segment(device), segment(records), recordCount, slotCount\n            );\n        } catch (Throwable throwable) {\n            return MemorySegment.NULL;\n        }\n    }\n\n    public static MemorySegment irisPlacementHeapTexture(\n            final MemorySegment owner, final long textureIndex\n    ) {\n        if (!irisPlacementHeapAvailable() || isNullHandle(owner) || textureIndex < 0L) {\n            return MemorySegment.NULL;\n        }\n        try {\n            return (MemorySegment) irisPlacementHeapTexture.invokeExact(segment(owner), textureIndex);\n        } catch (Throwable throwable) {\n            return MemorySegment.NULL;\n        }\n    }\n\n    public static MemorySegment metallum_create_texture_view(final MemorySegment texture, final long baseMipLevel, final long mipLevelCount) {'''
)

# ---------------------------------------------------------------------------
# MetalGpuTexture: permit adoption of one already-retained native texture.
# Renderer allocation identity and deferred release semantics remain unchanged.
# ---------------------------------------------------------------------------
texture = ROOT / "src/main/java/com/metallum/client/metal/render/MetalGpuTexture.java"
text = texture.read_text()
marker = '            final MetalTextureDimension dimension\n    ) {'
pos = text.index(marker)
start = text.rfind('    MetalGpuTexture(', 0, pos)
end = text.index('\n    int pixelSize()', pos)
old_block = text[start:end]
new_block = '''    MetalGpuTexture(\n            final MetalDevice device,\n            @GpuTexture.Usage final int usage,\n            final String label,\n            final GpuFormat format,\n            final int width,\n            final int height,\n            final int depthOrLayers,\n            final int mipLevels,\n            final MetalTextureDimension dimension\n    ) {\n        this(device, usage, label, format, width, height, depthOrLayers, mipLevels, dimension, null);\n    }\n\n    /** Adopts one retained native texture while keeping renderer-owned identity/lifetime. */\n    MetalGpuTexture(\n            final MetalDevice device,\n            @GpuTexture.Usage final int usage,\n            final String label,\n            final GpuFormat format,\n            final int width,\n            final int height,\n            final int depthOrLayers,\n            final int mipLevels,\n            final MemorySegment adoptedNativeHandle\n    ) {\n        this(\n                device, usage, label, format, width, height, depthOrLayers, mipLevels,\n                MetalTextureDimension.TWO_D, adoptedNativeHandle\n        );\n    }\n\n    private MetalGpuTexture(\n            final MetalDevice device,\n            @GpuTexture.Usage final int usage,\n            final String label,\n            final GpuFormat format,\n            final int width,\n            final int height,\n            final int depthOrLayers,\n            final int mipLevels,\n            final MetalTextureDimension dimension,\n            @Nullable final MemorySegment adoptedNativeHandle\n    ) {\n        super(usage, label, format, width, height, depthOrLayers, mipLevels);\n        this.device = device;\n        this.allocationIdentity = MetalAllocationIdentity.allocate(label);\n        this.mtlPixelFormat = MTLPixelFormat.from(format);\n\n        if (adoptedNativeHandle != null && !MetalNativeBridge.isNullHandle(adoptedNativeHandle)) {\n            this.nativeHandle = adoptedNativeHandle;\n        } else {\n            this.nativeHandle = MetalNativeBridge.metallum_create_texture(\n                    device.metalDeviceHandle(),\n                    this.mtlPixelFormat,\n                    width,\n                    height,\n                    depthOrLayers,\n                    mipLevels,\n                    dimension.nativeValue,\n                    (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,\n                    toMtlTextureUsage(usage),\n                    MTLStorageMode.Private,\n                    label\n            );\n        }\n        if (MetalNativeBridge.isNullHandle(this.nativeHandle)) {\n            throw new IllegalStateException(\n                    "Failed to create Metal " + dimension + " texture " + label + " ("\n                            + width + 'x' + height + 'x' + depthOrLayers + ", " + format + ')'\n            );\n        }\n    }\n'''
text = text[:start] + new_block + text[end:]
old_usage = '''    private long toMtlTextureUsage(@GpuTexture.Usage final int usage) {\n        long result = 0L;\n        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 || (usage & GpuTexture.USAGE_COPY_DST) != 0 || (usage & GpuTexture.USAGE_COPY_SRC) != 0) {\n            result |= MTLTextureUsage.ShaderRead.value;\n        }\n        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {\n            result |= MTLTextureUsage.RenderTarget.value;\n            result |= MTLTextureUsage.ShaderRead.value;\n            // Legacy path (kill switch only): blanket ShaderWrite on color\n            // attachments because MetalFX outputs used to rely on it. The\n            // minimal-usage path instead requires MetalFX output targets to\n            // carry USAGE_SHADER_WRITE explicitly (MetalDevice\n            // withExtraTextureUsage scope around their creation). Depth\n            // attachments must not receive ShaderWrite, because Metal does\n            // not permit storage writes to every depth format.\n            if (!MINIMAL_USAGE\n                    && !this.mtlPixelFormat.hasStencil() && this.mtlPixelFormat != MTLPixelFormat.Depth16Unorm\n                    && this.mtlPixelFormat != MTLPixelFormat.Depth32Float) {\n                result |= MTLTextureUsage.ShaderWrite.value;\n            }\n        }\n        if ((usage & USAGE_SHADER_WRITE) != 0) {\n            result |= MTLTextureUsage.ShaderWrite.value;\n        }\n        return result == 0L ? MTLTextureUsage.ShaderRead.value : result;\n    }\n'''
new_usage = '''    private long toMtlTextureUsage(@GpuTexture.Usage final int usage) {\n        return nativeUsageFor(this.mtlPixelFormat, usage);\n    }\n\n    static long nativeUsageFor(\n            final MTLPixelFormat pixelFormat, @GpuTexture.Usage final int usage\n    ) {\n        long result = 0L;\n        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0\n                || (usage & GpuTexture.USAGE_COPY_DST) != 0\n                || (usage & GpuTexture.USAGE_COPY_SRC) != 0) {\n            result |= MTLTextureUsage.ShaderRead.value;\n        }\n        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {\n            result |= MTLTextureUsage.RenderTarget.value;\n            result |= MTLTextureUsage.ShaderRead.value;\n            if (!MINIMAL_USAGE\n                    && !pixelFormat.hasStencil() && pixelFormat != MTLPixelFormat.Depth16Unorm\n                    && pixelFormat != MTLPixelFormat.Depth32Float) {\n                result |= MTLTextureUsage.ShaderWrite.value;\n            }\n        }\n        if ((usage & USAGE_SHADER_WRITE) != 0) {\n            result |= MTLTextureUsage.ShaderWrite.value;\n        }\n        return result == 0L ? MTLTextureUsage.ShaderRead.value : result;\n    }\n'''
if old_usage not in text:
    raise SystemExit('MetalGpuTexture usage mapper not found')
texture.write_text(text.replace(old_usage, new_usage, 1))

# ---------------------------------------------------------------------------
# New Java placement-heap set. Only alias-slot members use the heap; dedicated
# resources stay on the existing ordinary allocation path.
# ---------------------------------------------------------------------------
placement = ROOT / "src/main/java/com/metallum/client/metal/render/IrisMetalPlacementHeap.java"
placement.write_text(r'''package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.GpuFormat;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Physical placement-heap allocation for proven non-overlapping Iris colortex sides. */
final class IrisMetalPlacementHeap {
    private static final Pattern RESOURCE_KEY = Pattern.compile("colortex(\\d+)/(main|alt)/mip/(\\d+)");
    private static final int RECORD_LONGS = 6;

    record Request(String resourceKey, int target, boolean mainSide, int slot, int mipLevels, int usage) {
    }

    static final class Allocation {
        private final MemorySegment owner;
        private final MetalGpuTexture[] main;
        private final MetalGpuTexture[] alt;

        Allocation(
                final MemorySegment owner,
                final MetalGpuTexture[] main,
                final MetalGpuTexture[] alt
        ) {
            this.owner = owner;
            this.main = main;
            this.alt = alt;
        }

        @Nullable MetalGpuTexture main(final int index) {
            return main[index];
        }

        @Nullable MetalGpuTexture alt(final int index) {
            return alt[index];
        }

        void retireOwner(final MetalDevice device) {
            if (!MetalNativeBridge.isNullHandle(owner)) {
                device.queueResourceRelease(owner);
            }
        }
    }

    private IrisMetalPlacementHeap() {
    }

    static @Nullable Allocation tryCreate(
            final MetalDevice device,
            final String labelPrefix,
            final GpuFormat[] formats,
            final int width,
            final int height,
            final BitSet mipmappedTargets,
            final BitSet storageImageTargets
    ) {
        if (!"iris-colortex".equals(labelPrefix)
                || !MetalNativeBridge.irisPlacementHeapAvailable()) {
            return null;
        }
        IrisMetalHeapAliasRuntime.Published published = IrisMetalHeapAliasRuntime.current();
        if (published == null || published.slotByResource().isEmpty()) {
            return null;
        }

        List<Request> requests = new ArrayList<>();
        Set<String> seenSides = new HashSet<>();
        int maxSlot = -1;
        for (Map.Entry<String, Integer> entry : published.slotByResource().entrySet()) {
            Matcher matcher = RESOURCE_KEY.matcher(entry.getKey());
            if (!matcher.matches()) {
                return null;
            }
            int target;
            int mip;
            try {
                target = Integer.parseInt(matcher.group(1));
                mip = Integer.parseInt(matcher.group(3));
            } catch (NumberFormatException malformed) {
                return null;
            }
            // Current receipt binds whole colortex allocations through mip 0.
            // A future subresource-aware planner must get its own native ABI.
            if (target < 0 || target >= formats.length || mip != 0 || entry.getValue() < 0) {
                return null;
            }
            boolean mainSide = "main".equals(matcher.group(2));
            String sideKey = target + ":" + mainSide;
            if (!seenSides.add(sideKey)) {
                return null;
            }
            int mipLevels = mipmappedTargets.get(target)
                    ? fullMipLevelCount(width, height)
                    : 1;
            int usage = IrisMetalPingPongTargets.TEXTURE_USAGE
                    | (storageImageTargets.get(target) ? MetalGpuTexture.USAGE_SHADER_WRITE : 0);
            requests.add(new Request(
                    entry.getKey(), target, mainSide, entry.getValue(), mipLevels, usage
            ));
            maxSlot = Math.max(maxSlot, entry.getValue());
        }
        if (requests.size() < 2 || maxSlot < 0) {
            return null;
        }
        requests.sort(Comparator.comparingInt(Request::slot).thenComparing(Request::resourceKey));

        MemorySegment owner = MemorySegment.NULL;
        MemorySegment[] handles = new MemorySegment[requests.size()];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment records = arena.allocate(
                    Math.multiplyExact((long) requests.size() * RECORD_LONGS, Long.BYTES),
                    Long.BYTES
            );
            for (int index = 0; index < requests.size(); index++) {
                Request request = requests.get(index);
                long base = (long) index * RECORD_LONGS * Long.BYTES;
                MTLPixelFormat pixelFormat = MTLPixelFormat.from(formats[request.target()]);
                records.set(ValueLayout.JAVA_LONG, base, pixelFormat.value);
                records.set(ValueLayout.JAVA_LONG, base + 8L, width);
                records.set(ValueLayout.JAVA_LONG, base + 16L, height);
                records.set(ValueLayout.JAVA_LONG, base + 24L, request.mipLevels());
                records.set(
                        ValueLayout.JAVA_LONG, base + 32L,
                        MetalGpuTexture.nativeUsageFor(pixelFormat, request.usage())
                );
                records.set(ValueLayout.JAVA_LONG, base + 40L, request.slot());
            }
            owner = MetalNativeBridge.irisPlacementHeapCreate(
                    device.metalDeviceHandle(), records, requests.size(), (long) maxSlot + 1L
            );
            if (MetalNativeBridge.isNullHandle(owner)) {
                return null;
            }
            for (int index = 0; index < handles.length; index++) {
                handles[index] = MetalNativeBridge.irisPlacementHeapTexture(owner, index);
                if (MetalNativeBridge.isNullHandle(handles[index])) {
                    releaseHandles(handles);
                    MetalNativeBridge.metallum_release_object(owner);
                    return null;
                }
            }
        } catch (RuntimeException failure) {
            releaseHandles(handles);
            if (!MetalNativeBridge.isNullHandle(owner)) {
                MetalNativeBridge.metallum_release_object(owner);
            }
            return null;
        }

        MetalGpuTexture[] main = new MetalGpuTexture[formats.length];
        MetalGpuTexture[] alt = new MetalGpuTexture[formats.length];
        try {
            for (int index = 0; index < requests.size(); index++) {
                Request request = requests.get(index);
                String label = labelPrefix + request.target() + (request.mainSide() ? "-main" : "-alt");
                MetalGpuTexture texture = new MetalGpuTexture(
                        device,
                        request.usage(),
                        label,
                        formats[request.target()],
                        width,
                        height,
                        1,
                        request.mipLevels(),
                        handles[index]
                );
                handles[index] = MemorySegment.NULL; // ownership moved into texture
                if (request.mainSide()) {
                    main[request.target()] = texture;
                } else {
                    alt[request.target()] = texture;
                }
            }
            return new Allocation(owner, main, alt);
        } catch (RuntimeException constructionFailure) {
            for (MetalGpuTexture texture : main) if (texture != null) texture.close();
            for (MetalGpuTexture texture : alt) if (texture != null) texture.close();
            releaseHandles(handles);
            device.queueResourceRelease(owner);
            return null;
        }
    }

    private static int fullMipLevelCount(final int width, final int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    private static void releaseHandles(final MemorySegment[] handles) {
        for (int index = 0; index < handles.length; index++) {
            MemorySegment handle = handles[index];
            if (!MetalNativeBridge.isNullHandle(handle)) {
                MetalNativeBridge.metallum_release_object(handle);
                handles[index] = MemorySegment.NULL;
            }
        }
    }
}
''')

# ---------------------------------------------------------------------------
# Ping-pong targets consume the runtime recipe only for colortex. Failure is
# atomic: null allocation means every side follows the old device path.
# ---------------------------------------------------------------------------
ping = ROOT / "src/main/java/com/metallum/client/metal/render/IrisMetalPingPongTargets.java"
text = ping.read_text()
text = text.replace(
    '    private final BitSet mipmapsOnAlt;\n    private int width;',
    '    private final BitSet mipmapsOnAlt;\n    private IrisMetalPlacementHeap.Allocation placementAllocation;\n    private int width;',
    1
)
old = '''        this.mainSampleViews = new MetalGpuTextureView[formats.length];\n        this.altSampleViews = new MetalGpuTextureView[formats.length];\n        for (int index = 0; index < formats.length; index++) {'''
new = '''        this.mainSampleViews = new MetalGpuTextureView[formats.length];\n        this.altSampleViews = new MetalGpuTextureView[formats.length];\n        this.placementAllocation = IrisMetalPlacementHeap.tryCreate(\n                device, labelPrefix, formats, newWidth, newHeight,\n                mipmappedTargets, storageImageTargets\n        );\n        for (int index = 0; index < formats.length; index++) {'''
if old not in text: raise SystemExit('ping allocation insertion missing')
text = text.replace(old, new, 1)
old = '''            main[index] = (MetalGpuTexture) device.createTexture(\n                    labelPrefix + index + "-main", usage, formats[index], newWidth, newHeight, 1, mipLevels);\n            alt[index] = (MetalGpuTexture) device.createTexture(\n                    labelPrefix + index + "-alt", usage, formats[index], newWidth, newHeight, 1, mipLevels);'''
new = '''            MetalGpuTexture heapMain = placementAllocation == null ? null : placementAllocation.main(index);\n            MetalGpuTexture heapAlt = placementAllocation == null ? null : placementAllocation.alt(index);\n            main[index] = heapMain != null ? heapMain : (MetalGpuTexture) device.createTexture(\n                    labelPrefix + index + "-main", usage, formats[index], newWidth, newHeight, 1, mipLevels);\n            alt[index] = heapAlt != null ? heapAlt : (MetalGpuTexture) device.createTexture(\n                    labelPrefix + index + "-alt", usage, formats[index], newWidth, newHeight, 1, mipLevels);'''
if old not in text: raise SystemExit('ping texture construction missing')
text = text.replace(old, new, 1)
old = '''            if (alt[index] != null) {\n                alt[index].close();\n                alt[index] = null;\n            }\n        }\n    }'''
new = '''            if (alt[index] != null) {\n                alt[index].close();\n                alt[index] = null;\n            }\n        }\n        if (placementAllocation != null) {\n            placementAllocation.retireOwner(device);\n            placementAllocation = null;\n        }\n    }'''
if old not in text: raise SystemExit('ping owner retirement missing')
ping.write_text(text.replace(old, new, 1))

# Correct the recipe documentation now that the execution design is placement,
# not automatic-heap makeAliasable.
recipe = ROOT / "src/main/java/com/metallum/client/metal/render/IrisMetalHeapAliasRecipe.java"
text = recipe.read_text()
text = text.replace(
    'share one automatic MTLHeap allocation slot only when those intervals are\n * strictly disjoint. The emitted handoff edge is the only legal point at which\n * the executor may mark the former heap resource aliasable and create the next\n * resource. Native execution still has to prove GPU ordering at that edge.',
    'share one placement-heap backing range only when those intervals are\n * strictly disjoint. All resource objects may exist eagerly; placement at the\n * same aligned heap offset aliases their backing memory. The emitted handoff\n * edge remains the ordering proof: native execution must ensure the former\n * resource has no GPU use crossing that edge before the next aliased resource\n * is accessed.',
    1
)
recipe.write_text(text)

# ---------------------------------------------------------------------------
# Native placement owner and ABI. All descriptors match the existing private,
# untracked colortex allocation contract. Each returned texture is separately
# retained for Java; the owner keeps the heap alive until target teardown.
# ---------------------------------------------------------------------------
native = ROOT / "src/main/native/MetallumNative.swift"
text = native.read_text()
insert = r'''

private final class IrisPlacementHeapTextureSet {
    let heap: MTLHeap
    let textures: [MTLTexture]

    init(heap: MTLHeap, textures: [MTLTexture]) {
        self.heap = heap
        self.textures = textures
    }
}

private struct IrisPlacementTextureRecord {
    let descriptor: MTLTextureDescriptor
    let size: Int
    let alignment: Int
    let slot: Int
}

private func irisAlignUp(_ value: Int, _ alignment: Int) -> Int? {
    guard value >= 0, alignment > 0 else { return nil }
    let remainder = value % alignment
    if remainder == 0 { return value }
    let delta = alignment - remainder
    guard value <= Int.max - delta else { return nil }
    return value + delta
}

@_cdecl("metallum_iris_placement_heap_create")
public func metallum_iris_placement_heap_create(
    _ device: MTLDevice,
    _ rawRecords: UnsafePointer<UInt64>?,
    _ rawRecordCount: UInt64,
    _ rawSlotCount: UInt64
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let rawRecords,
              rawRecordCount > 1,
              rawRecordCount <= UInt64(Int.max),
              rawSlotCount > 0,
              rawSlotCount <= UInt64(Int.max) else {
            return nil
        }
        let recordCount = Int(rawRecordCount)
        let slotCount = Int(rawSlotCount)
        var records: [IrisPlacementTextureRecord] = []
        records.reserveCapacity(recordCount)
        var slotSize = Array(repeating: 0, count: slotCount)
        var slotAlignment = Array(repeating: 1, count: slotCount)

        for index in 0..<recordCount {
            let base = index * 6
            guard let format = MTLPixelFormat(rawValue: UInt(rawRecords[base])),
                  rawRecords[base + 1] > 0, rawRecords[base + 1] <= UInt64(Int.max),
                  rawRecords[base + 2] > 0, rawRecords[base + 2] <= UInt64(Int.max),
                  rawRecords[base + 3] > 0, rawRecords[base + 3] <= UInt64(Int.max),
                  rawRecords[base + 5] < rawSlotCount else {
                return nil
            }
            let descriptor = MTLTextureDescriptor()
            descriptor.textureType = .type2D
            descriptor.pixelFormat = format
            descriptor.width = Int(rawRecords[base + 1])
            descriptor.height = Int(rawRecords[base + 2])
            descriptor.depth = 1
            descriptor.mipmapLevelCount = Int(rawRecords[base + 3])
            descriptor.arrayLength = 1
            descriptor.sampleCount = 1
            descriptor.storageMode = .private
            descriptor.cpuCacheMode = .defaultCache
            descriptor.hazardTrackingMode = .untracked
            descriptor.usage = MTLTextureUsage(rawValue: UInt(rawRecords[base + 4]))

            let sizeAndAlign = device.heapTextureSizeAndAlign(descriptor: descriptor)
            guard sizeAndAlign.size > 0, sizeAndAlign.align > 0 else { return nil }
            let slot = Int(rawRecords[base + 5])
            slotSize[slot] = max(slotSize[slot], sizeAndAlign.size)
            slotAlignment[slot] = max(slotAlignment[slot], sizeAndAlign.align)
            records.append(IrisPlacementTextureRecord(
                descriptor: descriptor,
                size: sizeAndAlign.size,
                alignment: sizeAndAlign.align,
                slot: slot
            ))
        }

        var slotOffsets = Array(repeating: 0, count: slotCount)
        var heapSize = 0
        for slot in 0..<slotCount where slotSize[slot] > 0 {
            guard let aligned = irisAlignUp(heapSize, slotAlignment[slot]),
                  aligned <= Int.max - slotSize[slot] else {
                return nil
            }
            slotOffsets[slot] = aligned
            heapSize = aligned + slotSize[slot]
        }
        guard heapSize > 0 else { return nil }

        // max alignment is sufficient on Apple heap size/alignment values, but
        // verify every concrete descriptor before creating the heap rather than
        // assuming an undocumented divisibility relationship.
        for record in records {
            if slotOffsets[record.slot] % record.alignment != 0
                || slotOffsets[record.slot] > heapSize - record.size {
                return nil
            }
        }

        let heapDescriptor = MTLHeapDescriptor()
        heapDescriptor.type = .placement
        heapDescriptor.storageMode = .private
        heapDescriptor.cpuCacheMode = .defaultCache
        heapDescriptor.hazardTrackingMode = .untracked
        heapDescriptor.size = heapSize
        guard let heap = device.makeHeap(descriptor: heapDescriptor) else { return nil }
        heap.label = "Iris colortex placement heap"

        var textures: [MTLTexture] = []
        textures.reserveCapacity(recordCount)
        for (index, record) in records.enumerated() {
            guard let texture = heap.makeTexture(
                descriptor: record.descriptor,
                offset: slotOffsets[record.slot]
            ) else {
                return nil
            }
            texture.label = "Iris aliased colortex \(index)"
            residencyTrackCreated(texture)
            textures.append(texture)
        }
        return retainedPointer(IrisPlacementHeapTextureSet(heap: heap, textures: textures))
    }
}

@_cdecl("metallum_iris_placement_heap_texture")
public func metallum_iris_placement_heap_texture(
    _ ownerPointer: UnsafeMutableRawPointer?, _ rawIndex: UInt64
) -> UnsafeMutableRawPointer? {
    guard let ownerPointer, rawIndex <= UInt64(Int.max) else { return nil }
    let owner = Unmanaged<IrisPlacementHeapTextureSet>.fromOpaque(ownerPointer).takeUnretainedValue()
    let index = Int(rawIndex)
    guard index >= 0, index < owner.textures.count else { return nil }
    return retainedPointer(owner.textures[index])
}
'''
needle = '@_cdecl("metallum_create_texture_view")\npublic func metallum_create_texture_view'
if needle not in text:
    raise SystemExit('native texture view insertion point missing')
text = text.replace(needle, insert + '\n\n' + needle, 1)
native.write_text(text)

# Source contract locks the execution shape even on hosted runners that cannot
# validate physical aliasing behavior.
test = ROOT / "src/test/java/com/metallum/client/metal/render/IrisMetalPlacementHeapContractTest.java"
test.write_text(r'''package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalPlacementHeapContractTest {
    @Test
    void placementHeapUsesExplicitOverlappingOffsetsAndFailsClosed() throws Exception {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        String javaSource = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalPlacementHeap.java"
        ));
        String targets = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalPingPongTargets.java"
        ));
        String recipe = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalHeapAliasRecipe.java"
        ));

        assertTrue(nativeSource.contains("heapDescriptor.type = .placement"));
        assertTrue(nativeSource.contains("heapTextureSizeAndAlign(descriptor: descriptor)"));
        assertTrue(nativeSource.contains("offset: slotOffsets[record.slot]"));
        assertTrue(nativeSource.contains("descriptor.hazardTrackingMode = .untracked"));
        assertTrue(javaSource.contains("IrisMetalHeapAliasRuntime.current()"));
        assertTrue(javaSource.contains("mip != 0"));
        assertTrue(javaSource.contains("return null;"));
        assertTrue(targets.contains("placementAllocation = IrisMetalPlacementHeap.tryCreate"));
        assertTrue(targets.contains("heapMain != null ? heapMain"));
        assertTrue(targets.contains("placementAllocation.retireOwner(device)"));
        assertTrue(recipe.contains("placement-heap backing range"));
        assertTrue(recipe.contains("ordering proof"));
    }
}
''')

print('placement heap execution patch applied')
