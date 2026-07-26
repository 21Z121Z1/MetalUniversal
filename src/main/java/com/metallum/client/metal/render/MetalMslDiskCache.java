package com.metallum.client.metal.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metallum.Metallum;
import com.mojang.blaze3d.GpuFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Disk cache for the GLSL→SPIR-V→MSL translation result of one render
 * pipeline: the five-tuple consumed by
 * {@link MetalCompiledRenderPipeline}'s constructor. A hit skips shaderc and
 * SPIRV-Cross entirely; {@code makeLibrary} still runs (Metal's own shader
 * cache absorbs that) and PSO-level caching is the binary archive's job.
 *
 * <p>One JSON file per key under {@code <gameDir>/metallum-cache/msl/}
 * (overridable via {@code METALLUM_MSL_CACHE_DIR}; an empty value disables
 * the cache, as does {@code -Dmetallum.opt.mslCache=false}). Corrupt files
 * are deleted and treated as misses; store failures are logged and ignored —
 * the cache must never block startup.
 */
@Environment(EnvType.CLIENT)
final class MetalMslDiskCache {
    /**
     * Bump the version suffix whenever any code change can alter the
     * translation output for identical inputs: spvc compiler options
     * (MSL version, FLIP_VERTEX_Y, decoration binding, texture buffer
     * native), {@code applySampleLodBias} rewriting, entry-point
     * extraction, or binding assignment in {@code addToBindGroup}.
     */
    static final String CACHE_SALT = "metallum-msl-v1";

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("metallum.opt.mslCache", "true"));

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger MISSES = new AtomicInteger();
    private static final AtomicLong TRANSLATE_NANOS = new AtomicLong();

    private static @Nullable MetalMslDiskCache instance;
    private static boolean initAttempted;

    private final Path directory;

    private MetalMslDiskCache(final Path directory) {
        this.directory = directory;
    }

    record Entry(String vertexMsl, String fragmentMsl, String vertexEntryPoint, String fragmentEntryPoint,
                 List<MetalCompiledRenderPipeline.ResourceBinding> resources) {
    }

    /** Returns the shared cache, or {@code null} when disabled/unavailable. */
    static synchronized @Nullable MetalMslDiskCache instance() {
        if (!initAttempted) {
            initAttempted = true;
            if (ENABLED) {
                try {
                    Path directory = resolveDirectory();
                    if (directory != null) {
                        Files.createDirectories(directory);
                        instance = new MetalMslDiskCache(directory);
                    }
                } catch (Exception e) {
                    Metallum.LOGGER.warn("[metallum] MSL disk cache unavailable; translating uncached", e);
                }
            }
        }
        return instance;
    }

    private static @Nullable Path resolveDirectory() {
        String override = System.getenv("METALLUM_MSL_CACHE_DIR");
        if (override != null) {
            return override.isBlank() ? null : Path.of(override);
        }
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getGameDir().resolve("metallum-cache").resolve("msl");
    }

    /**
     * SHA-256 over the given segments joined with {@code '\0'}, lowercase
     * hex. Callers must pass <b>every</b> input that can influence the
     * translated five-tuple; see the call site in
     * {@link MetalCrossShaderCompiler} for the segment inventory.
     */
    static String key(final String... segments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String segment : segments) {
                digest.update(segment.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Nullable
    Entry load(final String key) {
        Path file = this.directory.resolve(key + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            List<MetalCompiledRenderPipeline.ResourceBinding> resources = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("resources")) {
                JsonObject binding = element.getAsJsonObject();
                JsonElement texelFormat = binding.get("texelFormat");
                resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                        MetalCompiledRenderPipeline.ResourceKind.valueOf(binding.get("kind").getAsString()),
                        binding.get("name").getAsString(),
                        binding.get("bindingIndex").getAsInt(),
                        binding.get("stageMask").getAsInt(),
                        texelFormat == null || texelFormat.isJsonNull() ? null : GpuFormat.valueOf(texelFormat.getAsString())
                ));
            }
            return new Entry(
                    root.get("vertexMsl").getAsString(),
                    root.get("fragmentMsl").getAsString(),
                    root.get("vertexEntryPoint").getAsString(),
                    root.get("fragmentEntryPoint").getAsString(),
                    List.copyOf(resources)
            );
        } catch (Exception e) {
            // Corrupt or stale-schema entry: drop it and recompile.
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
            Metallum.LOGGER.warn("[metallum] discarded corrupt MSL cache entry {}", file.getFileName());
            return null;
        }
    }

    void store(final String key, final Entry entry) {
        JsonObject root = new JsonObject();
        root.addProperty("vertexMsl", entry.vertexMsl());
        root.addProperty("fragmentMsl", entry.fragmentMsl());
        root.addProperty("vertexEntryPoint", entry.vertexEntryPoint());
        root.addProperty("fragmentEntryPoint", entry.fragmentEntryPoint());
        JsonArray resources = new JsonArray();
        // Order is preserved verbatim: bindingIndex-derived masks and the
        // resources() iteration order both depend on it.
        for (MetalCompiledRenderPipeline.ResourceBinding binding : entry.resources()) {
            JsonObject serialized = new JsonObject();
            serialized.addProperty("kind", binding.kind().name());
            serialized.addProperty("name", binding.name());
            serialized.addProperty("bindingIndex", binding.bindingIndex());
            serialized.addProperty("stageMask", binding.stageMask());
            GpuFormat texelFormat = binding.texelBufferFormat();
            serialized.addProperty("texelFormat", texelFormat == null ? null : texelFormat.name());
            resources.add(serialized);
        }
        root.add("resources", resources);
        Path file = this.directory.resolve(key + ".json");
        Path temp = this.directory.resolve(key + ".tmp");
        try {
            Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Metallum.LOGGER.warn("[metallum] failed to store MSL cache entry", e);
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
            }
        }
    }

    static void recordHit() {
        HITS.incrementAndGet();
    }

    static void recordMiss(final long translateNanos) {
        MISSES.incrementAndGet();
        TRANSLATE_NANOS.addAndGet(translateNanos);
    }

    /** One-line cumulative session stats; silent when the cache never ran. */
    static void logSessionStats() {
        int hits = HITS.get();
        int misses = MISSES.get();
        if (hits + misses > 0) {
            Metallum.LOGGER.info("[metallum] MSL disk cache: {} hits, {} misses ({} ms translating)",
                    hits, misses, TRANSLATE_NANOS.get() / 1_000_000L);
        }
    }
}
