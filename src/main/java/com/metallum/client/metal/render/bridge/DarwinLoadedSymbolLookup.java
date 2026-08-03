package com.metallum.client.metal.render.bridge;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves a symbol from the already loaded Metallum image on Darwin.
 *
 * <p>The main bridge uses {@link SymbolLookup#libraryLookup(Path, Arena)} on
 * macOS, which intentionally keeps a private lookup and loads from a random
 * temporary path. Re-extracting that resource here would instantiate a second
 * Swift image with a second set of process globals. Instead this helper walks
 * dyld's loaded-image table, obtains the existing image with RTLD_NOLOAD, and
 * performs dlsym on that handle.</p>
 */
final class DarwinLoadedSymbolLookup {
    private static final int RTLD_LAZY = 0x1;
    private static final int RTLD_NOLOAD = 0x10;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle IMAGE_COUNT = downcall(
            "_dyld_image_count",
            FunctionDescriptor.of(ValueLayout.JAVA_INT)
    );
    private static final MethodHandle IMAGE_NAME = downcall(
            "_dyld_get_image_name",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );
    private static final MethodHandle DLOPEN = downcall(
            "dlopen",
            FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
            )
    );
    private static final MethodHandle DLSYM = downcall(
            "dlsym",
            FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS
            )
    );
    /** Kept for process lifetime; do not dlclose the image that owns live handles. */
    private static MemorySegment metallumHandle = MemorySegment.NULL;

    private DarwinLoadedSymbolLookup() {
    }

    static @Nullable MemorySegment find(final String symbol) {
        if (!isDarwin() || IMAGE_COUNT == null || IMAGE_NAME == null || DLOPEN == null || DLSYM == null) {
            return null;
        }
        synchronized (DarwinLoadedSymbolLookup.class) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment handle = metallumHandle;
                if (handle.address() == 0L) {
                    handle = locateMetallumHandle(arena);
                    if (handle.address() == 0L) {
                        return null;
                    }
                    metallumHandle = handle;
                }
                MemorySegment symbolName = arena.allocateFrom(symbol);
                MemorySegment address = (MemorySegment) DLSYM.invokeExact(handle, symbolName);
                return address.address() == 0L ? null : address;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static MemorySegment locateMetallumHandle(final Arena arena) throws Throwable {
        int count = (int) IMAGE_COUNT.invokeExact();
        for (int index = 0; index < count; index++) {
            MemorySegment namePointer = (MemorySegment) IMAGE_NAME.invokeExact(index);
            if (namePointer.address() == 0L) {
                continue;
            }
            String imagePath = namePointer.reinterpret(Long.MAX_VALUE).getString(0L);
            String lower = imagePath.toLowerCase(Locale.ROOT);
            if (!lower.contains("metallum") || !lower.endsWith(".dylib")) {
                continue;
            }
            MemorySegment path = arena.allocateFrom(imagePath);
            MemorySegment handle = (MemorySegment) DLOPEN.invokeExact(
                    path,
                    RTLD_LAZY | RTLD_NOLOAD
            );
            if (handle.address() != 0L) {
                return handle;
            }
        }
        return MemorySegment.NULL;
    }

    private static @Nullable MethodHandle downcall(
            final String symbol,
            final FunctionDescriptor descriptor
    ) {
        try {
            return LINKER.defaultLookup().find(symbol)
                    .map(address -> LINKER.downcallHandle(address, descriptor))
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isDarwin() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin") || os.contains("ios");
    }
}
