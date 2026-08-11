package com.metallum.client.metal.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.systems.DeviceInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Opt-in final-target evidence for physical Iris runs.
 *
 * <p>The recorder is deliberately inert unless a receipt path is supplied.
 * When enabled, every sampled frame is copied from the final Metal texture
 * into a CPU-visible buffer before its hash and pixel metrics are written.
 * A failed copy is fatal to the run: a missing readback is not a pass.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalRuntimeReceipts implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String RECEIPT_PROPERTY = "metallum.iris.validation.receipt";
    private static final String CAPTURE_PROPERTY = "metallum.iris.validation.captureDir";
    private static final String CAPTURE_EVERY_PROPERTY = "metallum.iris.validation.captureEvery";
    private static final String SHADOW_STAGES_PROPERTY = "metallum.iris.validation.shadowStages";
    private static final String EXPECTED_CODE_SOURCE_PROPERTY =
            "metallum.iris.validation.expectedCodeSource";
    private static final String EXPECTED_CODE_HASH_PROPERTY =
            "metallum.iris.validation.expectedCodeSourceSha256";
    private static final String REQUIRE_CODE_IDENTITY_PROPERTY =
            "metallum.iris.validation.requireCodeIdentity";
    private static final String ARTIFACT_JAR_PROPERTY =
            "metallum.iris.artifactJar";
    private static final String EXPECTED_ARTIFACT_JAR_HASH_PROPERTY =
            "metallum.iris.validation.expectedArtifactJarSha256";
    private static final String EXPECTED_NATIVE_DYLIB_HASH_PROPERTY =
            "metallum.iris.validation.expectedNativeDylibSha256";
    private static final int DEFAULT_CAPTURE_EVERY = 1;
    private static final int MAX_CAPTURE_BYTES = 512 * 1024 * 1024;

    private final int generation;
    private final Path receiptPath;
    private final Path captureDirectory;
    private final int captureEvery;
    private final BufferedWriter writer;
    private long frameIndex;
    private byte[] previousFrame;
    private boolean deviceIdentityRecorded;
    private boolean closed;

    private IrisMetalRuntimeReceipts(
            final int generation,
            final Path receiptPath,
            final Path captureDirectory,
            final int captureEvery,
            final BufferedWriter writer
    ) {
        this.generation = generation;
        this.receiptPath = receiptPath;
        this.captureDirectory = captureDirectory;
        this.captureEvery = captureEvery;
        this.writer = writer;
        writeSession();
    }

    static IrisMetalRuntimeReceipts open(final int generation) {
        String configured = System.getProperty(RECEIPT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return new IrisMetalRuntimeReceipts();
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("Iris receipt generation must be positive: " + generation);
        }
        Path receipt = Path.of(configured).toAbsolutePath().normalize();
        Path capture = configuredCaptureDirectory();
        int captureEvery = configuredCaptureEvery();
        try {
            Path parent = receipt.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (capture != null) {
                Files.createDirectories(capture);
            }
            BufferedWriter writer = Files.newBufferedWriter(
                    receipt,
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
            return new IrisMetalRuntimeReceipts(generation, receipt, capture, captureEvery, writer);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not open Iris runtime receipt " + receipt, failure);
        }
    }

    private IrisMetalRuntimeReceipts() {
        this.generation = 0;
        this.receiptPath = null;
        this.captureDirectory = null;
        this.captureEvery = 0;
        this.writer = null;
    }

    void recordEvent(final String event) {
        if (!enabled()) {
            return;
        }
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("Iris runtime receipt event must be non-empty");
        }
        JsonObject object = baseObject("event");
        object.addProperty("event", event);
        object.addProperty("frame", frameIndex);
        write(object);
    }

    void recordColorSpaceFinalization(
            final ColorSpace colorSpace,
            final boolean conversionExecuted,
            final boolean packOwnedBypass
    ) {
        if (!enabled()) {
            return;
        }
        Objects.requireNonNull(colorSpace, "colorSpace");
        JsonObject object = baseObject("color-space");
        object.addProperty("event", "color-space.finalized");
        object.addProperty("frame", frameIndex);
        object.addProperty("colorSpace", colorSpace.name());
        object.addProperty("conversionExecuted", conversionExecuted);
        object.addProperty("packOwnedBypass", packOwnedBypass);
        object.addProperty(
                "mode",
                packOwnedBypass ? "pack-owned-bypass"
                        : conversionExecuted ? "iris-conversion" : "identity"
        );
        write(object);
    }

    void recordFailure(final String phase, final Throwable failure) {
        if (!enabled()) {
            return;
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("Iris runtime receipt failure phase must be non-empty");
        }
        Objects.requireNonNull(failure, "failure");
        JsonObject object = baseObject("failure");
        object.addProperty("phase", phase);
        object.addProperty("error", failure.toString());
        write(object);
    }

    /**
     * Records a discarded generation candidate with the extent and rebuild
     * reason that caused the attempt. This keeps a failed resize or device
     * handoff distinguishable from an ordinary shader/runtime failure.
     */
    void recordGenerationCandidateFailure(
            final String phase,
            final Throwable failure,
            final int width,
            final int height,
            final boolean resizing,
            final boolean deviceReplacement
    ) {
        if (!enabled()) {
            return;
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException(
                    "Iris generation candidate failure phase must be non-empty"
            );
        }
        Objects.requireNonNull(failure, "failure");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Iris generation candidate failure extent must be positive: "
                            + width + "x" + height
            );
        }
        JsonObject object = baseObject("generation");
        object.addProperty("event", "candidate-discarded");
        object.addProperty("status", "discarded");
        object.addProperty("frame", frameIndex);
        object.addProperty("phase", phase);
        object.addProperty("error", failure.toString());
        object.addProperty("width", width);
        object.addProperty("height", height);
        object.addProperty("resizing", resizing);
        object.addProperty("deviceReplacement", deviceReplacement);
        write(object);
    }

    void recordDeviceIdentity(final MetalDevice device) {
        if (!enabled() || deviceIdentityRecorded) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("Iris runtime receipt is already closed");
        }
        DeviceInfo info = device.getDeviceInfo();
        JsonObject object = baseObject("device");
        addDeviceDescriptor(object, info);
        object.addProperty("nativeDylibResource", nativeDylibResource());
        String dylibHash = resourceSha256(nativeDylibResource());
        if (dylibHash != null) {
            object.addProperty("nativeDylibSha256", dylibHash);
        }
        String expectedDylibHash = System.getProperty(EXPECTED_NATIVE_DYLIB_HASH_PROPERTY);
        if (expectedDylibHash != null && !expectedDylibHash.isBlank()) {
            boolean matches = expectedDylibHash.equalsIgnoreCase(
                    dylibHash == null ? "" : dylibHash
            );
            object.addProperty("expectedNativeDylibSha256", expectedDylibHash);
            object.addProperty("nativeDylibIdentityMatched", matches);
            if (!matches && Boolean.parseBoolean(
                    System.getProperty(REQUIRE_CODE_IDENTITY_PROPERTY, "false")
            )) {
                throw new IllegalStateException(
                        "Iris runtime native dylib identity mismatch: expected "
                                + expectedDylibHash + ", actual " + dylibHash
                );
            }
        }
        object.addProperty("sourceCommit", sourceCommit());
        addCodeIdentity(object);
        write(object);
        deviceIdentityRecorded = true;
    }

    static void addDeviceDescriptor(final JsonObject object, final DeviceInfo info) {
        object.addProperty("backend", info.backendName());
        object.addProperty("name", info.name());
        object.addProperty("vendor", info.vendorName());
        object.addProperty("driver", info.driverInfo());
        // "type" belongs to the receipt record schema. Keep the GPU class in
        // its own field so an integrated/discrete classification cannot turn
        // a device record into an unknown record type.
        object.addProperty("deviceType", info.type().name());
    }

    void captureFinalTarget(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final GpuTextureView finalTarget
    ) {
        if (!enabled()) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("Iris runtime receipt is already closed");
        }
        if (!(finalTarget.texture() instanceof MetalGpuTexture texture)) {
            throw new IllegalStateException("Iris final target is not owned by the Metal backend");
        }
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        int pixelSize = texture.pixelSize();
        long byteCount = Math.multiplyExact(
                Math.multiplyExact((long) width, height), pixelSize
        );
        if (byteCount <= 0L || byteCount > MAX_CAPTURE_BYTES) {
            throw new IllegalStateException(
                    "Iris final target readback has unsupported size " + byteCount + " bytes"
            );
        }

        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "Iris final target receipt",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                byteCount
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            device.waitForSubmittedGpuWork();
            ByteBuffer mapped = buffer.currentStorage();
            mapped.limit(Math.toIntExact(byteCount));
            byte[] bytes = new byte[Math.toIntExact(byteCount)];
            mapped.get(bytes);
            FrameMetrics metrics = analyze(bytes, width, height, pixelSize, previousFrame);
            Path capturePath = null;
            if (captureDirectory != null && frameIndex % captureEvery == 0L) {
                capturePath = captureDirectory.resolve(
                        String.format(Locale.ROOT, "final-%06d.rgba", frameIndex)
                );
                Files.write(
                        capturePath,
                        bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            }
            JsonObject object = baseObject("final-frame");
            object.addProperty("frame", frameIndex);
            object.addProperty("width", width);
            object.addProperty("height", height);
            object.addProperty("pixelSize", pixelSize);
            object.addProperty("format", texture.getFormat().toString());
            object.addProperty("sha256", metrics.sha256());
            object.addProperty("fnv1a64", Long.toUnsignedString(metrics.fnv1a64(), 16));
            object.addProperty("nonBlackRgbPixels", metrics.nonBlackRgbPixels());
            object.addProperty("changedPixels", metrics.changedPixels());
            object.addProperty("meanAbsoluteByteDelta", metrics.meanAbsoluteByteDelta());
            object.addProperty("maxByte", metrics.maxByte());
            object.addProperty("sumRgbBytes", metrics.sumRgbBytes());
            object.addProperty("hasPreviousFrame", metrics.hasPreviousFrame());
            if (capturePath != null) {
                object.addProperty("capture", capturePath.toString());
            }
            write(object);
            previousFrame = bytes;
            frameIndex++;
        } catch (IOException | RuntimeException failure) {
            if (enabled()) {
                JsonObject error = baseObject("error");
                error.addProperty("frame", frameIndex);
                error.addProperty("operation", "final-target-readback");
                error.addProperty("message", failure.toString());
                write(error);
            }
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new UncheckedIOException((IOException) failure);
        }
    }

    /**
     * Captures the generation-owned shadow scene after geometry submission and
     * mipmap production, before shadow composite consumes it. A final-frame
     * hash cannot prove that shadow terrain/entities actually populated the
     * shadow ABI, so this receipt records depth occupancy and color content
     * from the real Metal textures.
     */
    void captureShadowTargets(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final IrisMetalShadowTargets targets,
            final ShadowDrawMetrics drawMetrics
    ) {
        if (!enabled() || closed || frameIndex % captureEvery != 0L) {
            return;
        }
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(drawMetrics, "drawMetrics");
        try {
            double expectedHardwareClear = MetalIrisDepthConvention.hardwareClear(1.0);
            TextureMetrics depth0 = readTexture(
                    device, encoder, targets.shadowDepthTexture(), expectedHardwareClear
            );
            TextureMetrics depth1 = readTexture(
                    device, encoder, targets.shadowDepthNoTranslucentsTexture(), expectedHardwareClear
            );
            JsonObject object = baseObject("shadow-frame");
            object.addProperty("frame", frameIndex);
            object.addProperty("resolution", targets.resolution());
            addDepthMetrics(object, "shadowtex0", depth0, expectedHardwareClear);
            addDepthMetrics(object, "shadowtex1", depth1, expectedHardwareClear);
            long nonZeroColorBytes = 0L;
            long colorBytes = 0L;
            for (int index = 0; index < targets.colorTargets().targetCount(); index++) {
                // A logical target may have flipped during shadowcomp. Read
                // both physical sides so a receipt cannot mistake a stale
                // main-side snapshot for the complete shadow-color ABI.
                TextureMetrics main = readTexture(
                        device,
                        encoder,
                        targets.colorTargets().mainTexture(index),
                        expectedHardwareClear
                );
                TextureMetrics alt = readTexture(
                        device,
                        encoder,
                        targets.colorTargets().altTexture(index),
                        expectedHardwareClear
                );
                nonZeroColorBytes += main.nonZeroBytes() + alt.nonZeroBytes();
                colorBytes += main.byteCount() + alt.byteCount();
                object.addProperty("shadowcolor" + index + "MainSha256", main.sha256());
                object.addProperty("shadowcolor" + index + "MainNonZeroBytes", main.nonZeroBytes());
                object.addProperty("shadowcolor" + index + "AltSha256", alt.sha256());
                object.addProperty("shadowcolor" + index + "AltNonZeroBytes", alt.nonZeroBytes());
            }
            object.addProperty("shadowcolorNonZeroBytes", nonZeroColorBytes);
            object.addProperty("shadowcolorBytes", colorBytes);
            object.addProperty("shadowTerrainPasses", drawMetrics.terrainPasses());
            object.addProperty("shadowTerrainDrawCalls", drawMetrics.terrainDrawCalls());
            object.addProperty("shadowTerrainIndexCount", drawMetrics.terrainIndexCount());
            object.addProperty("shadowCoreDrawCalls", drawMetrics.coreDrawCalls());
            object.addProperty("shadowDrawObserved", drawMetrics.anyDrawSubmitted());
            // Color targets may contain a clear value or stale bytes that are
            // unrelated to geometry. Only depth occupancy is a content-level
            // proof for the shadow scene.
            object.addProperty("shadowContentObserved",
                    drawMetrics.anyDrawSubmitted()
                            && (depth0.nonClearDepthSamples() > 0L
                            || depth1.nonClearDepthSamples() > 0L));
            write(object);
        } catch (RuntimeException failure) {
            JsonObject error = baseObject("error");
            error.addProperty("frame", frameIndex);
            error.addProperty("operation", "shadow-target-readback");
            error.addProperty("message", failure.toString());
            write(error);
            throw failure;
        }
    }

    /**
     * Validation-only readback at a live shadow-scene boundary. This is kept
     * separate from the normal end-of-scene receipt so a failing or overwritten
     * shadowtex1 can be located without making staged readbacks part of normal
     * rendering.
     */
    void captureShadowStage(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final IrisMetalShadowTargets targets,
            final String stage
    ) {
        if (!shadowStagesEnabled() || !enabled() || closed || frameIndex % captureEvery != 0L) {
            return;
        }
        if (stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("Iris shadow readback stage must be non-empty");
        }
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(targets, "targets");
        try {
            double expectedHardwareClear = MetalIrisDepthConvention.hardwareClear(1.0);
            TextureMetrics depth0 = readTexture(
                    device, encoder, targets.shadowDepthTexture(), expectedHardwareClear
            );
            TextureMetrics depth1 = readTexture(
                    device, encoder, targets.shadowDepthNoTranslucentsTexture(), expectedHardwareClear
            );
            JsonObject object = baseObject("shadow-stage");
            object.addProperty("frame", frameIndex);
            object.addProperty("stage", stage);
            object.addProperty("resolution", targets.resolution());
            addDepthMetrics(object, "shadowtex0", depth0, expectedHardwareClear);
            addDepthMetrics(object, "shadowtex1", depth1, expectedHardwareClear);
            write(object);
        } catch (RuntimeException failure) {
            JsonObject error = baseObject("error");
            error.addProperty("frame", frameIndex);
            error.addProperty("operation", "shadow-stage-readback:" + stage);
            error.addProperty("message", failure.toString());
            write(error);
            throw failure;
        }
    }

    private static void addDepthMetrics(
            final JsonObject object,
            final String name,
            final TextureMetrics metrics,
            final double expectedHardwareClear
    ) {
        object.addProperty(name + "Sha256", metrics.sha256());
        object.addProperty(name + "FiniteSamples", metrics.finiteDepthSamples());
        object.addProperty(name + "NonClearSamples", metrics.nonClearDepthSamples());
        object.addProperty(name + "ExpectedHardwareClear", expectedHardwareClear);
        object.addProperty(name + "Min", metrics.minDepth());
        object.addProperty(name + "Max", metrics.maxDepth());
    }

    private TextureMetrics readTexture(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final MetalGpuTexture texture,
            final double expectedHardwareClear
    ) {
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        int pixelSize = texture.pixelSize();
        long byteCount = Math.multiplyExact(Math.multiplyExact((long) width, height), pixelSize);
        if (byteCount <= 0L || byteCount > MAX_CAPTURE_BYTES) {
            throw new IllegalStateException(
                    "Iris shadow target readback has unsupported size " + byteCount + " bytes"
            );
        }
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "Iris shadow target receipt",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                byteCount
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            device.waitForSubmittedGpuWork();
            ByteBuffer mapped = buffer.currentStorage().duplicate().order(ByteOrder.nativeOrder());
            mapped.limit(Math.toIntExact(byteCount));
            byte[] bytes = new byte[Math.toIntExact(byteCount)];
            mapped.get(bytes);
            return TextureMetrics.of(
                    texture, bytes, width, height, pixelSize, expectedHardwareClear
            );
        }
    }

    private void writeSession() {
        if (!enabled()) {
            return;
        }
        JsonObject object = baseObject("session");
        object.addProperty("receipt", receiptPath.toString());
        object.addProperty("captureEvery", captureEvery);
        object.addProperty("javaVersion", System.getProperty("java.version", "unknown"));
        object.addProperty("irisVersion", safeIrisVersion());
        object.addProperty("pack", safePackName());
        object.addProperty("metalfxMode", System.getProperty("metallum.metalfx.mode", "unset"));
        object.addProperty(
                "frameGeneration",
                System.getProperty("metallum.metalfx.frameGeneration", "unset")
        );
        addCodeIdentity(object);
        write(object);
    }

    private JsonObject baseObject(final String type) {
        JsonObject object = new JsonObject();
        object.addProperty("schema", "iris-metal-runtime-receipt-v1");
        object.addProperty("type", type);
        object.addProperty("generation", generation);
        return object;
    }

    private void addCodeIdentity(final JsonObject object) {
        String actualPath = null;
        String actualHash = null;
        try {
            var source = IrisMetalRuntimeReceipts.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                URI uri = source.getLocation().toURI();
                Path path = Path.of(uri).toAbsolutePath().normalize();
                actualPath = path.toString();
                object.addProperty("codeSource", actualPath);
                if (Files.isRegularFile(path)) {
                    actualHash = sha256(Files.readAllBytes(path));
                    object.addProperty("codeSourceSha256", actualHash);
                }
            }
        } catch (Exception failure) {
            object.addProperty("codeIdentityError", failure.toString());
        }
        addExpectedCodeIdentity(object, actualPath, actualHash);
        addArtifactIdentity(object);
    }

    private static void addArtifactIdentity(final JsonObject object) {
        String configured = System.getProperty(ARTIFACT_JAR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return;
        }
        Path artifact = Path.of(configured).toAbsolutePath().normalize();
        object.addProperty("artifactJar", artifact.toString());
        String actualHash = null;
        try {
            if (!Files.isRegularFile(artifact)) {
                throw new IOException("artifact JAR is not a regular file: " + artifact);
            }
            actualHash = sha256(Files.readAllBytes(artifact));
            object.addProperty("artifactJarSha256", actualHash);
        } catch (IOException failure) {
            object.addProperty("artifactJarIdentityError", failure.toString());
        }
        String expectedHash = System.getProperty(EXPECTED_ARTIFACT_JAR_HASH_PROPERTY);
        if (expectedHash != null && !expectedHash.isBlank()) {
            object.addProperty("expectedArtifactJarSha256", expectedHash);
            boolean matches = expectedHash.equalsIgnoreCase(actualHash == null ? "" : actualHash);
            object.addProperty("artifactJarIdentityMatched", matches);
            if (!matches && Boolean.parseBoolean(
                    System.getProperty(REQUIRE_CODE_IDENTITY_PROPERTY, "false")
            )) {
                throw new IllegalStateException(
                        "Iris runtime artifact JAR identity mismatch: expected "
                                + expectedHash + ", actual " + actualHash
                );
            }
        }
    }

    private static void addExpectedCodeIdentity(
            final JsonObject object,
            final String actualPath,
            final String actualHash
    ) {
        String expectedPath = System.getProperty(EXPECTED_CODE_SOURCE_PROPERTY);
        String expectedHash = System.getProperty(EXPECTED_CODE_HASH_PROPERTY);
        if (expectedPath != null && !expectedPath.isBlank()) {
            object.addProperty(
                    "expectedCodeSource",
                    Path.of(expectedPath).toAbsolutePath().normalize().toString()
            );
        }
        if (expectedHash != null && !expectedHash.isBlank()) {
            object.addProperty("expectedCodeSourceSha256", expectedHash);
        }
        if ((expectedPath == null || expectedPath.isBlank())
                && (expectedHash == null || expectedHash.isBlank())) {
            return;
        }
        boolean pathMatches = expectedPath == null || expectedPath.isBlank()
                || (actualPath != null
                && Path.of(actualPath).toAbsolutePath().normalize().toString()
                .equals(Path.of(expectedPath).toAbsolutePath().normalize().toString()));
        boolean hashMatches = expectedHash == null || expectedHash.isBlank()
                || (actualHash != null && expectedHash.equalsIgnoreCase(actualHash));
        boolean matches = pathMatches && hashMatches;
        object.addProperty("codeIdentityMatched", matches);
        if (!matches && Boolean.parseBoolean(
                System.getProperty(REQUIRE_CODE_IDENTITY_PROPERTY, "false")
        )) {
            throw new IllegalStateException(
                    "Iris runtime code identity mismatch: expected path/hash "
                            + expectedPath + "/" + expectedHash
                            + ", actual " + actualPath + "/" + actualHash
            );
        }
    }

    private static String sourceCommit() {
        for (String property : new String[]{
                "metallum.iris.sourceCommit", "metallum.sourceCommit", "git.commit"
        }) {
            String value = System.getProperty(property);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        for (String variable : new String[]{"METALLUM_IRIS_SOURCE_COMMIT", "GIT_COMMIT"}) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown";
    }

    private static String nativeDylibResource() {
        return MetalNativeBridge.isIOS()
                ? "/natives/ios/libmetallum.dylib"
                : "/natives/macos/libmetallum.dylib";
    }

    private static @org.jspecify.annotations.Nullable String resourceSha256(final String resource) {
        try (InputStream stream = IrisMetalRuntimeReceipts.class.getResourceAsStream(resource)) {
            return stream == null ? null : sha256(stream.readAllBytes());
        } catch (IOException failure) {
            return null;
        }
    }

    private void write(final JsonObject object) {
        try {
            writer.write(GSON.toJson(object));
            writer.newLine();
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write Iris runtime receipt " + receiptPath, failure);
        }
    }

    private boolean enabled() {
        return writer != null;
    }

    private static Path configuredCaptureDirectory() {
        String configured = System.getProperty(CAPTURE_PROPERTY);
        return configured == null || configured.isBlank()
                ? null
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static int configuredCaptureEvery() {
        String configured = System.getProperty(CAPTURE_EVERY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_CAPTURE_EVERY;
        }
        try {
            int value = Integer.parseInt(configured);
            if (value <= 0) {
                throw new NumberFormatException("must be positive");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "Invalid " + CAPTURE_EVERY_PROPERTY + ": " + configured, failure
            );
        }
    }

    private static boolean shadowStagesEnabled() {
        return Boolean.parseBoolean(System.getProperty(SHADOW_STAGES_PROPERTY, "false"));
    }

    private static String safeIrisVersion() {
        try {
            return Iris.getVersion();
        } catch (Throwable failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    private static String safePackName() {
        try {
            return Iris.getCurrentPackName();
        } catch (Throwable failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    static FrameMetrics analyze(
            final byte[] bytes,
            final int width,
            final int height,
            final int pixelSize,
            final byte[] previous
    ) {
        if (bytes == null || width <= 0 || height <= 0 || pixelSize <= 0
                || bytes.length != Math.multiplyExact(Math.multiplyExact(width, height), pixelSize)) {
            throw new IllegalArgumentException("Invalid final-target receipt buffer shape");
        }
        long nonBlack = 0L;
        long changedPixels = 0L;
        long absoluteDelta = 0L;
        long sumRgb = 0L;
        int maxByte = 0;
        for (int pixel = 0; pixel < width * height; pixel++) {
            int base = pixel * pixelSize;
            boolean nonBlackPixel = false;
            for (int channel = 0; channel < Math.min(3, pixelSize); channel++) {
                int value = Byte.toUnsignedInt(bytes[base + channel]);
                nonBlackPixel |= value != 0;
                sumRgb += value;
                maxByte = Math.max(maxByte, value);
            }
            if (nonBlackPixel) {
                nonBlack++;
            }
            if (previous != null) {
                boolean changed = false;
                for (int byteIndex = 0; byteIndex < pixelSize; byteIndex++) {
                    int current = Byte.toUnsignedInt(bytes[base + byteIndex]);
                    int old = Byte.toUnsignedInt(previous[base + byteIndex]);
                    int delta = Math.abs(current - old);
                    absoluteDelta += delta;
                    changed |= delta != 0;
                }
                if (changed) {
                    changedPixels++;
                }
            }
        }
        return new FrameMetrics(
                sha256(bytes),
                fnv1a64(bytes),
                nonBlack,
                changedPixels,
                previous == null ? 0.0D : (double) absoluteDelta / bytes.length,
                maxByte,
                sumRgb,
                previous != null
        );
    }

    private static long fnv1a64(final byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= Byte.toUnsignedLong(value);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    record ShadowDrawMetrics(
            long terrainPasses,
            long terrainDrawCalls,
            long terrainIndexCount,
            long coreDrawCalls
    ) {
        boolean anyDrawSubmitted() {
            return this.terrainDrawCalls > 0L || this.coreDrawCalls > 0L;
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError("JRE does not provide SHA-256", failure);
        }
    }

    @Override
    public void close() {
        if (!enabled() || closed) {
            return;
        }
        closed = true;
        recordEvent("generation-retired");
        try {
            writer.close();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not close Iris runtime receipt " + receiptPath, failure);
        }
    }

    record FrameMetrics(
            String sha256,
            long fnv1a64,
            long nonBlackRgbPixels,
            long changedPixels,
            double meanAbsoluteByteDelta,
            int maxByte,
            long sumRgbBytes,
            boolean hasPreviousFrame
    ) {
    }

    private record TextureMetrics(
            String sha256,
            long byteCount,
            long nonZeroBytes,
            long finiteDepthSamples,
            long nonClearDepthSamples,
            double minDepth,
            double maxDepth
    ) {
        private static TextureMetrics of(
                final MetalGpuTexture texture,
                final byte[] bytes,
                final int width,
                final int height,
                final int pixelSize,
                final double expectedHardwareClear
        ) {
            long nonZero = 0L;
            for (byte value : bytes) {
                if (value != 0) {
                    nonZero++;
                }
            }
            long finite = 0L;
            long nonClear = 0L;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            if (texture.getFormat() == com.mojang.blaze3d.GpuFormat.D32_FLOAT && pixelSize == Float.BYTES) {
                ByteBuffer values = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
                for (int index = 0; index < width * height; index++) {
                    float depth = values.getFloat();
                    if (Float.isFinite(depth)) {
                        finite++;
                        min = Math.min(min, depth);
                        max = Math.max(max, depth);
                        if (Math.abs(depth - expectedHardwareClear) > 0.000001F) {
                            nonClear++;
                        }
                    }
                }
            }
            return new TextureMetrics(
                    IrisMetalRuntimeReceipts.sha256(bytes),
                    bytes.length,
                    nonZero,
                    finite,
                    nonClear,
                    finite == 0L ? Double.NaN : min,
                    finite == 0L ? Double.NaN : max
            );
        }
    }
}
