package com.metallum.e2e;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only immutable initial world artifacts; all I/O is outside observation windows. */
final class WorldSnapshot {
    private WorldSnapshot() { }
    /** Flush/copy/hash only before warmup, on the server thread; never inside frame collection. */
    static JsonObject capture(Path source, Path destination, JsonObject recipe) {
        try {
            java.security.MessageDigest manifestDigest = java.security.MessageDigest.getInstance("SHA-256");
            JsonArray files = new JsonArray();
            Files.createDirectories(destination);
            try (var paths = Files.walk(source)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = source.relativize(path).toString().replace('\\', '/');
                    if (relative.equals("session.lock")) continue;
                    require(!Files.isSymbolicLink(path), "Initial world contains a symbolic link: " + relative);
                    Path copy = destination.resolve(relative);
                    Files.createDirectories(copy.getParent());
                    Files.copy(path, copy);
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    try (var stream = Files.newInputStream(copy)) {
                        byte[] buffer = new byte[65536];
                        for (int count; (count = stream.read(buffer)) != -1;) digest.update(buffer, 0, count);
                    }
                    String sha256 = java.util.HexFormat.of().formatHex(digest.digest());
                    JsonObject file = new JsonObject();
                    file.addProperty("path", relative);
                    file.addProperty("sha256", sha256);
                    file.addProperty("bytes", Files.size(copy));
                    files.add(file);
                    manifestDigest.update((relative + "\0" + sha256 + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            require(!files.isEmpty() && Files.isRegularFile(destination.resolve("level.dat")), "Initial snapshot is incomplete");
            JsonObject content = recipe.deepCopy();
            content.remove("saveDirectory");
            content.remove("waypoints");
            content.addProperty("kind", "flushed-world-snapshot");
            content.addProperty("snapshotSha256", java.util.HexFormat.of().formatHex(manifestDigest.digest()));
            content.addProperty("snapshotDirectory", destination.getFileName().toString());
            content.addProperty("snapshotHashAlgorithm", "sha256(sorted(relative-path + NUL + file-sha256 + LF))");
            content.addProperty("scope", "Saved initial content before warmup; live entities/ticks continue normally; fresh seed alone is not comparison identity");
            JsonObject camera = new JsonObject();
            camera.addProperty("x", 160.5); camera.addProperty("y", 140); camera.addProperty("z", 160.5);
            camera.addProperty("yaw", -65); camera.addProperty("pitch", 15);
            content.add("camera", camera);
            content.addProperty("requestedTime", "noon");
            content.addProperty("requestedWeather", "clear");
            JsonObject manifest = new JsonObject();
            manifest.add("identity", content.deepCopy());
            manifest.add("files", files);
            write(destination.resolveSibling(destination.getFileName() + "-manifest.json"), manifest);
            return content;
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot preserve initial world identity", failure);
        }
    }

    static String verify(Path snapshot) {
        try {
            JsonObject manifest = JsonParser.parseString(Files.readString(snapshot.resolveSibling(
                    snapshot.getFileName() + "-manifest.json"))).getAsJsonObject();
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            java.util.Set<String> listed = new java.util.HashSet<>();
            String previous = "";
            for (var entry : manifest.getAsJsonArray("files")) {
                JsonObject file = entry.getAsJsonObject();
                String relative = file.get("path").getAsString();
                Path path = snapshot.resolve(relative).normalize();
                require(path.startsWith(snapshot) && !relative.equals("session.lock") && listed.add(relative)
                        && relative.compareTo(previous) > 0, "Invalid snapshot manifest path/order");
                previous = relative;
                require(Files.isRegularFile(path) && !Files.isSymbolicLink(path), "Missing snapshot file: " + relative);
                var fileDigest = java.security.MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[65536];
                    for (int count; (count = input.read(buffer)) != -1;) fileDigest.update(buffer, 0, count);
                }
                String actual = java.util.HexFormat.of().formatHex(fileDigest.digest());
                require(actual.equals(file.get("sha256").getAsString()) && Files.size(path) == file.get("bytes").getAsLong(),
                        "Snapshot file identity mismatch: " + relative);
                digest.update((relative + "\0" + actual + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try (var files = Files.walk(snapshot)) {
                require(files.filter(Files::isRegularFile).count() == listed.size(), "Unlisted snapshot content");
            }
            String sha = java.util.HexFormat.of().formatHex(digest.digest());
            require(sha.equals(manifest.getAsJsonObject("identity").get("snapshotSha256").getAsString())
                    && listed.contains("level.dat"), "Snapshot manifest identity mismatch");
            return sha;
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot verify initial snapshot", failure);
        }
    }

    static void copyVerified(Path source, Path destination) throws IOException {
        verify(source);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                require(!Files.isSymbolicLink(path), "Snapshot symlink forbidden");
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static void write(Path path, JsonObject value) throws IOException {
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(value) + "\n");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
