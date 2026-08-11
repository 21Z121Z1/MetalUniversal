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
    private static final int MAX_IMAGE_PATH_BYTES = 16 * 1024;
    /** Kept for process lifetime; do not dlclose the image that owns live handles. */
    private static MemorySegment metallumHandle = MemorySegment.NULL;

    private DarwinLoadedSymbolLookup() {
    }

    static @Nullable MemorySegment find(final String symbol) {
        if (!isDarwin() || !DarwinApi.AVAILABLE) {
            return null;
        }
        synchronized (DarwinLoadedSymbolLookup.class) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment symbolName = arena.allocateFrom(symbol);
                if (metallumHandle.address() != 0L) {
                    MemorySegment cached = lookup(metallumHandle, symbolName);
                    if (cached.address() != 0L) {
                        return cached;
                    }
                    // A future interface symbol may come from a differently
                    // named image. Fall through and scan instead of pinning a
                    // negative result to the first successful handle forever.
                }

                int count = (int) DarwinApi.IMAGE_COUNT.invokeExact();
                for (int index = 0; index < count; index++) {
                    MemorySegment namePointer = (MemorySegment) DarwinApi.IMAGE_NAME.invokeExact(index);
                    if (namePointer.address() == 0L) {
                        continue;
                    }
                    String imagePath = namePointer.reinterpret(MAX_IMAGE_PATH_BYTES).getString(0L);
                    if (!isMetallumImagePath(imagePath)) {
                        continue;
                    }
                    MemorySegment path = arena.allocateFrom(imagePath);
                    MemorySegment handle = (MemorySegment) DarwinApi.DLOPEN.invokeExact(
                            path,
                            RTLD_LAZY | RTLD_NOLOAD
                    );
                    if (handle.address() == 0L) {
                        continue;
                    }
                    MemorySegment address = lookup(handle, symbolName);
                    if (address.address() != 0L) {
                        metallumHandle = handle;
                        return address;
                    }
                }
                return null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    static boolean isMetallumImagePath(final String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return false;
        }
        String normalized = imagePath.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String fileName = normalized.substring(separator + 1).toLowerCase(Locale.ROOT);
        return fileName.endsWith(".dylib")
                && (fileName.startsWith("libmetallum")
                || fileName.startsWith("metallum-native-"));
    }

    private static MemorySegment lookup(
            final MemorySegment handle,
            final MemorySegment symbolName
    ) throws Throwable {
        return (MemorySegment) DarwinApi.DLSYM.invokeExact(handle, symbolName);
    }

    private static boolean isDarwin() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin") || os.contains("ios");
    }

    /** Defers restricted FFM initialization until a Darwin symbol is requested. */
    private static final class DarwinApi {
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
        private static final boolean AVAILABLE = IMAGE_COUNT != null
                && IMAGE_NAME != null
                && DLOPEN != null
                && DLSYM != null;

        private DarwinApi() {
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
    }
}
