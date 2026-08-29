package com.metallum.client.metal.render;

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
