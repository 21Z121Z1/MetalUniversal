package com.metallum.client.metal.render.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DarwinLoadedSymbolLookupTest {
    @Test
    void acceptsShippingAndExtractedMetallumImages() {
        assertTrue(DarwinLoadedSymbolLookup.isMetallumImagePath(
                "/private/tmp/metallum-native-12345.dylib"
        ));
        assertTrue(DarwinLoadedSymbolLookup.isMetallumImagePath(
                "/Applications/Amethyst.app/Frameworks/libmetallum.dylib"
        ));
        assertTrue(DarwinLoadedSymbolLookup.isMetallumImagePath(
                "/Applications/Amethyst.app/Frameworks/libmetallum_native.dylib"
        ));
    }

    @Test
    void rejectsAdjacentLibrariesAndNonDylibs() {
        assertFalse(DarwinLoadedSymbolLookup.isMetallumImagePath(
                "/tmp/libspvc_metallum.dylib"
        ));
        assertFalse(DarwinLoadedSymbolLookup.isMetallumImagePath(
                "/tmp/libmetallum.so"
        ));
        assertFalse(DarwinLoadedSymbolLookup.isMetallumImagePath(""));
        assertFalse(DarwinLoadedSymbolLookup.isMetallumImagePath(null));
    }
}
