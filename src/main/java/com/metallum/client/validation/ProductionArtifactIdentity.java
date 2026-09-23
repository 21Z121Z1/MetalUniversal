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

/** Opt-in provenance observation, performed once before the measured workload. */
final class ProductionArtifactIdentity {
    private static boolean recorded;

    private ProductionArtifactIdentity() { }

    static void recordOnce(Path directory) {
        if (recorded || !Boolean.getBoolean("metallum.validation.productionArtifact")) return;
        try {
            Path jar = Path.of(Class.forName("com.metallum.client.metal.render.MetalDevice").getProtectionDomain().getCodeSource().getLocation().toURI());
            require(Files.isRegularFile(jar), "Physical production validation loaded development classes: " + jar);
            String renderer = System.getProperty("metallum.ci.renderer", "unrecorded");
            require(renderer.equals("vanilla") || renderer.equals("sodium") || renderer.equals("iris"),
                    "An explicit runtime renderer profile is required");
            FabricLoader loader = FabricLoader.getInstance();
            require(loader.isModLoaded("sodium") == !renderer.equals("vanilla"), "Unexpected Sodium runtime presence");
            require(loader.isModLoaded("iris") == renderer.equals("iris"), "Unexpected Iris runtime presence");
            JsonObject report = new JsonObject();
            report.addProperty("rendererMode", renderer);
            report.addProperty("loadedJavaArtifact", jar.toString());
            try (InputStream input = Files.newInputStream(jar)) {
                report.addProperty("javaArtifactSha256", sha256(input));
            }
            try (ZipFile archive = new ZipFile(jar.toFile())) {
                try (InputStream input = archive.getInputStream(archive.getEntry("metallum-build-identity.json"))) {
                    JsonObject build = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    String expected = System.getProperty("metallum.validation.sourceCommit", "unrecorded");
                    require(expected.matches("[0-9a-f]{40}") && expected.equals(build.get("sourceSha").getAsString()),
                            "Loaded production JAR source does not match the requested exact commit");
                    require(!build.get("dirty").getAsBoolean(), "Production JAR came from a dirty checkout");
                    report.add("build", build);
                }
                try (InputStream input = archive.getInputStream(archive.getEntry("natives/macos/libmetallum.dylib"))) {
                    report.addProperty("packagedNativeSha256", sha256(input));
                }
            }
            Path nativeFile = MetalNativeBridge.loadedLibraryFileForDiagnostics();
            require(nativeFile != null && Files.isRegularFile(nativeFile), "The actually loaded native library path is unavailable");
            report.addProperty("loadedNativePath", nativeFile.toString());
            try (InputStream input = Files.newInputStream(nativeFile)) {
                String actual = sha256(input);
                require(actual.equals(report.get("packagedNativeSha256").getAsString()), "Loaded native library differs from the production JAR");
                report.addProperty("loadedNativeSha256", actual);
            }
            JsonObject mods = new JsonObject();
            loader.getAllMods().stream().sorted(java.util.Comparator.comparing(mod -> mod.getMetadata().getId()))
                    .forEach(mod -> mods.addProperty(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
            report.add("mods", mods);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("artifact-identity.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            recorded = true;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not observe the loaded production artifact", exception);
        }
    }

    private static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
