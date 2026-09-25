package com.metallum.client.validation;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/** Validation-only observation of the code and native file actually loaded in this JVM. */
public final class RuntimeArtifactIdentity {
    private RuntimeArtifactIdentity() {}

    public static void write(Path output) {
        try {
            Path jar = Path.of(Class.forName("com.metallum.client.metal.render.MetalDevice").getProtectionDomain().getCodeSource().getLocation().toURI());
            require(Files.isRegularFile(jar), "Production validation loaded development classes: " + jar);
            String mode = System.getProperty("metallum.ci.rendererMode", "unrecorded");
            require(mode.equals("vanilla") || mode.equals("sodium") || mode.equals("iris"), "Unknown renderer mode: " + mode);
            FabricLoader loader = FabricLoader.getInstance();
            require(loader.isModLoaded("sodium") == !mode.equals("vanilla"), "Sodium runtime presence mismatch");
            require(loader.isModLoaded("iris") == mode.equals("iris"), "Iris runtime presence mismatch");
            JsonObject result = new JsonObject();
            result.addProperty("rendererMode", mode);
            result.addProperty("loadedJavaArtifact", jar.toString());
            String jarHash;
            try (InputStream input = Files.newInputStream(jar)) { jarHash = sha256(input); }
            result.addProperty("javaArtifactSha256", jarHash);
            expectedHash("metallum.ci.p1ProductionJarSha256", jarHash);
            String nativeHash;
            try (ZipFile archive = new ZipFile(jar.toFile())) {
                var identityEntry = archive.getEntry("metallum-build-identity.json");
                require(identityEntry != null, "Production JAR has no build identity");
                try (InputStream input = archive.getInputStream(identityEntry)) {
                    JsonObject build = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    String expected = System.getProperty("metallum.ci.expectedSourceSha", "");
                    require(expected.matches("[0-9a-f]{40}"), "Exact expected source SHA is required");
                    require(expected.equals(build.get("sourceSha").getAsString()), "Loaded JAR source SHA mismatch");
                    require(!build.get("dirty").getAsBoolean(), "Production JAR was built from a dirty checkout");
                    result.add("build", build);
                }
                var nativeEntry = archive.getEntry("natives/macos/libmetallum.dylib");
                require(nativeEntry != null, "Production JAR has no macOS native binary");
                try (InputStream input = archive.getInputStream(nativeEntry)) { nativeHash = sha256(input); }
                result.addProperty("packagedNativeSha256", nativeHash);
            }
            Path loadedNative = MetalNativeBridge.loadedLibraryFileForDiagnostics();
            require(loadedNative != null && Files.isRegularFile(loadedNative), "Actual native load path is unavailable");
            String loadedHash;
            try (InputStream input = Files.newInputStream(loadedNative)) { loadedHash = sha256(input); }
            require(nativeHash.equals(loadedHash), "Loaded native differs from packaged native");
            expectedHash("metallum.ci.p1NativeDylibSha256", loadedHash);
            result.addProperty("loadedNativeArtifact", loadedNative.toString());
            result.addProperty("loadedNativeSha256", loadedHash);
            JsonObject mods = new JsonObject();
            loader.getAllMods().stream().sorted(java.util.Comparator.comparing(mod -> mod.getMetadata().getId()))
                    .forEach(mod -> mods.addProperty(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
            result.add("mods", mods);
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
        } catch (Exception failure) {
            throw new IllegalStateException("Loaded production artifact identity failed", failure);
        }
    }

    private static void expectedHash(String property, String actual) {
        String expected = System.getProperty(property, "unrecorded");
        if (!expected.equals("unrecorded")) {
            require(expected.matches("[0-9a-f]{64}") && expected.equals(actual), property + " mismatch");
        }
    }

    private static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
