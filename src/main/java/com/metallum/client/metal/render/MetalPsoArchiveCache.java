package com.metallum.client.metal.render;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;

/** Compatibility namespace for Metal's internally shader-keyed pipeline archives. */
final class MetalPsoArchiveCache {
    private MetalPsoArchiveCache() { }

    static Path path(final Path directory, final String deviceName, final boolean metal4Compiler,
                     final @Nullable Path nativeLibrary, final Path systemBuild,
                     final Map<String, String> environment) throws IOException {
        if (nativeLibrary == null) {
            throw new IOException("PSO archive identity unavailable: loaded native library has no file");
        }
        var identity = new ArrayList<String>();
        identity.add("metallum-pso-archive-v2");
        identity.add(deviceName);
        identity.add(Boolean.toString(metal4Compiler));
        identity.add(System.getProperty("os.name"));
        identity.add(System.getProperty("os.arch"));
        identity.add(fileHash(systemBuild));
        // Hash bytes, not the randomly named extraction path or Java commit:
        // identical native builds must retain their warm cache across launches.
        identity.add(fileHash(nativeLibrary));
        environment.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("MTL_")
                        || entry.getKey().startsWith("METAL_")
                        || entry.getKey().equals("DYLD_INSERT_LIBRARIES")
                        || entry.getKey().equals("METALLUM_PSO_COMPILE_HOP"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    identity.add(entry.getKey());
                    identity.add(entry.getValue());
                });
        // Reuse the existing length-prefixed fingerprint to avoid ambiguous
        // field boundaries. Unscoped legacy archives are preserved, never read.
        return directory.resolve("pso-v2-" + MetalMslDiskCache.key(identity.toArray(String[]::new)) + ".binaryarchive");
    }

    private static String fileHash(final Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
