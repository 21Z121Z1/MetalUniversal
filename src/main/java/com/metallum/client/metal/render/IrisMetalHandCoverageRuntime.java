package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exact first-person coverage for Iris hand programs consumed by MetalFX.
 *
 * <p>Iris keeps pack-facing depth in forward-Z while its semantic layer is active.
 * Transparent hand programs are therefore not required to write depth, so post-hand
 * D32 depth cannot be used as a coverage mask. This runtime appends a generation-owned
 * R8 color target to every Iris hand PSO. The target is cleared to 1.0 (forward-Z far)
 * at the depthtex2/hand boundary and surviving hand fragments write 0.0 (forward-Z near).
 * The existing native hand-overlay kernel can consume that texture as an exact depth
 * surrogate without changing the Java/FFM/Swift ABI or the shader pack's depth writes.</p>
 */
public final class IrisMetalHandCoverageRuntime {
    private static final String COVERAGE_OUTPUT = "metallum_MetalFxHandCoverage";
    private static final Pattern MAIN = Pattern.compile(
            "\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{"
    );
    private static final int COVERAGE_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final Vector4fc CLEAR_FAR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final ColorTargetState COVERAGE_TARGET = new ColorTargetState(
            Optional.empty(),
            GpuFormat.R8_UNORM,
            ColorTargetState.WRITE_RED
    );
    private static final ThreadLocal<SyntheticBuild> SYNTHETIC_BUILD = new ThreadLocal<>();

    private static final Field RENDER_TARGETS = instanceField(
            IrisMetalPipelineOverrides.Instance.class, "renderTargets"
    );
    private static final Field MANAGER_ACTIVE = staticField(MetalFxManager.class, "active");
    private static final Field MANAGER_DEVICE = instanceField(MetalFxManager.class, "device");
    private static final Field MANAGER_OBJECT_MOTION = instanceField(MetalFxManager.class, "objectMotionTexture");
    private static final Field MANAGER_OBJECT_VALIDITY = instanceField(MetalFxManager.class, "objectValidityTexture");
    private static final Field MANAGER_REACTIVE = instanceField(MetalFxManager.class, "reactiveTexture");
    private static final Field MANAGER_WORLD_DEPTH = instanceField(MetalFxManager.class, "frameDepthTexture");
    private static final Field MANAGER_RENDER_WIDTH = instanceField(MetalFxManager.class, "renderWidth");
    private static final Field MANAGER_RENDER_HEIGHT = instanceField(MetalFxManager.class, "renderHeight");
    private static final Field MANAGER_DEPTH_REVERSED = instanceField(MetalFxManager.class, "frameDepthReversed");
    private static final Field MANAGER_HAND_REACTIVE_BOOST = staticField(
            MetalFxManager.class, "HAND_OVERLAY_REACTIVE_BOOST"
    );

    private static @Nullable IrisMetalPipelineOverrides.Instance owner;
    private static @Nullable MetalGpuTexture coverageTexture;
    private static @Nullable MetalGpuTextureView coverageView;
    private static int coverageWidth;
    private static int coverageHeight;
    private static long frameSerial;
    private static long readyFrameSerial = Long.MIN_VALUE;
    private static boolean loggedOverlayFailure;

    private IrisMetalHandCoverageRuntime() {
    }

    /** Invalidates the previous frame before any hand boundary can arm coverage again. */
    public static void beginFrame() {
        frameSerial++;
        readyFrameSerial = Long.MIN_VALUE;
    }

    /**
     * Runs immediately after Iris freezes depthtex2. This is the exact semantic boundary
     * between world/translucent rendering and first-person hand rendering.
     */
    public static void beginHandPhase() {
        IrisMetalPipelineOverrides.Instance instance = IrisMetalPipelineOverrides.active();
        MetalDevice device = MetalDevice.current();
        if (instance == null || device == null || IrisMetalPipelineOverrides.isShadowPassActive()) {
            readyFrameSerial = Long.MIN_VALUE;
            return;
        }
        if (MetalIrisDepthConvention.metalFxDepthReversed()) {
            throw new IllegalStateException(
                    "Iris MetalFX hand coverage requires the active Iris forward-Z depth convention"
            );
        }
        IrisMetalRenderTargets targets = renderTargets(instance);
        if (targets == null) {
            readyFrameSerial = Long.MIN_VALUE;
            return;
        }
        ensureCoverageTarget(device, instance, targets.width(), targets.height());
        device.commandEncoder().clearColorTexture(requireCoverageTexture(), CLEAR_FAR);
        readyFrameSerial = frameSerial;
    }

    /** Called before buildCoreSynthetic emits generated GLSL and target state. */
    public static void beginSyntheticBuild(final Object keyObject, final Object programObject) {
        SYNTHETIC_BUILD.remove();
        if (!(keyObject instanceof ShaderKey key) || !isHandKey(key)) {
            return;
        }
        if (!(programObject instanceof MetalIrisShaderCompiler.GlslProgram program)) {
            throw new IllegalArgumentException(
                    "Iris hand coverage expected MetalIrisShaderCompiler.GlslProgram, got "
                            + (programObject == null ? "null" : programObject.getClass().getName())
            );
        }
        int location = program.drawBuffers().length;
        if (location < 0 || location >= ColorTargetState.MAX_COLOR_TARGETS) {
            throw new IllegalStateException(
                    "Iris hand DRAWBUFFERS leave no color attachment for MetalFX coverage: " + location
            );
        }
        SYNTHETIC_BUILD.set(new SyntheticBuild(location));
    }

    /** Rewrites only the fragment-source Map.put selected by the pipeline mixin. */
    public static Object transformGeneratedFragment(final Object sourceObject) {
        SyntheticBuild build = SYNTHETIC_BUILD.get();
        if (build == null) {
            return sourceObject;
        }
        if (!(sourceObject instanceof String source)) {
            throw new IllegalArgumentException("Iris generated hand fragment source is not a String");
        }
        return injectCoverageOutput(source, build.location());
    }

    /** Appends the R8 PSO target at the compact slot after the pack's DRAWBUFFERS. */
    public static RenderPipeline finishSyntheticBuild(final RenderPipeline.Builder builder) {
        SyntheticBuild build = SYNTHETIC_BUILD.get();
        try {
            if (build != null) {
                builder.withColorTargetState(build.location(), COVERAGE_TARGET);
            }
            return builder.build();
        } finally {
            SYNTHETIC_BUILD.remove();
        }
    }

    /**
     * Appends the persistent R8 view to the actual core hand render pass. The PSO already
     * owns the matching target; failure to arm the lifecycle is therefore rejected instead
     * of submitting a descriptor with a different attachment signature.
     */
    public static RenderPassDescriptor appendToHandDescriptor(final RenderPassDescriptor descriptor) {
        if (!HandRenderer.INSTANCE.isActive() || IrisMetalPipelineOverrides.isShadowPassActive()) {
            return descriptor;
        }
        IrisMetalPipelineOverrides.Instance instance = IrisMetalPipelineOverrides.active();
        if (instance == null) {
            return descriptor;
        }
        if (owner != instance || readyFrameSerial != frameSerial || coverageView == null) {
            throw new IllegalStateException(
                    "Iris hand draw reached Metal without exact hand coverage being armed at depthtex2"
            );
        }
        descriptor.withColorAttachment(coverageView, Optional.empty());
        return descriptor;
    }

    /**
     * Before the fused temporal kernel runs, consume exact coverage through the already
     * validated hand-overlay compute path. This stamps objectValidity=1, objectMotion=0
     * and the configured reactive boost for first-person pixels. The fused kernel then
     * selects those object vectors before consulting the legacy D32 hand-depth fallback.
     */
    public static void prepareExactMotionBeforeTemporalEncode() {
        MetalGpuTexture exactCoverage = exactCoverageForCurrentFrame();
        if (exactCoverage == null) {
            return;
        }
        Object manager = getStatic(MANAGER_ACTIVE);
        if (manager == null) {
            return;
        }
        MetalDevice device = get(MANAGER_DEVICE, manager, MetalDevice.class);
        MetalGpuTexture objectMotion = get(MANAGER_OBJECT_MOTION, manager, MetalGpuTexture.class);
        MetalGpuTexture objectValidity = get(MANAGER_OBJECT_VALIDITY, manager, MetalGpuTexture.class);
        MetalGpuTexture reactive = get(MANAGER_REACTIVE, manager, MetalGpuTexture.class);
        MetalGpuTexture worldDepth = get(MANAGER_WORLD_DEPTH, manager, MetalGpuTexture.class);
        if (device == null || objectMotion == null || objectValidity == null
                || reactive == null || worldDepth == null) {
            return;
        }
        int width = getInt(MANAGER_RENDER_WIDTH, manager);
        int height = getInt(MANAGER_RENDER_HEIGHT, manager);
        boolean depthReversed = getBoolean(MANAGER_DEPTH_REVERSED, manager);
        if (depthReversed || MetalIrisDepthConvention.metalFxDepthReversed()) {
            throw new IllegalStateException(
                    "Iris exact hand coverage was encoded as forward-Z but MetalFX requested reverse-Z"
            );
        }
        if (width != coverageWidth || height != coverageHeight
                || worldDepth.getWidth(0) != width || worldDepth.getHeight(0) != height) {
            throw new IllegalStateException(
                    "Iris hand coverage extent " + coverageWidth + "x" + coverageHeight
                            + " does not match MetalFX temporal extent " + width + "x" + height
            );
        }
        float boost = getFloat(MANAGER_HAND_REACTIVE_BOOST, null);
        boolean prepared = device.commandEncoder().encodeHandOverlayMotion(
                exactCoverage,
                worldDepth,
                objectMotion,
                objectValidity,
                reactive,
                width,
                height,
                boost,
                false
        );
        if (!prepared && !loggedOverlayFailure) {
            loggedOverlayFailure = true;
            Metallum.LOGGER.warn(
                    "Iris exact hand coverage could not be consumed; MetalFX retains the legacy hand-depth fallback"
            );
        }
    }

    /** Releases generation-owned coverage before the owning Iris instance tears down. */
    public static void releaseOwner(final Object instance) {
        if (owner != instance) {
            return;
        }
        releaseCoverageTarget();
        owner = null;
        readyFrameSerial = Long.MIN_VALUE;
    }

    static boolean isHandKey(final ShaderKey key) {
        return key == ShaderKey.HAND_CUTOUT
                || key == ShaderKey.HAND_CUTOUT_DIFFUSE
                || key == ShaderKey.HAND_WATER_DIFFUSE
                || key == ShaderKey.HAND_TRANSLUCENT
                || key == ShaderKey.HAND_TEXT
                || key == ShaderKey.HAND_TEXT_TRANSLUCENT;
    }

    static String injectCoverageOutput(final String source, final int location) {
        if (location < 0 || location >= ColorTargetState.MAX_COLOR_TARGETS) {
            throw new IllegalArgumentException("Invalid MetalFX hand coverage location " + location);
        }
        if (source.contains(COVERAGE_OUTPUT)) {
            throw new IllegalArgumentException(
                    "Generated Iris fragment source already declares reserved output " + COVERAGE_OUTPUT
            );
        }
        Matcher main = MAIN.matcher(source);
        if (!main.find()) {
            throw new IllegalArgumentException(
                    "Generated Iris hand fragment shader has no supported void main() entry point"
            );
        }
        int declarationAt = main.start();
        int bodyAt = main.end();
        String declaration = "layout(location = " + location + ") out float "
                + COVERAGE_OUTPUT + ";\n";
        // The texture is a forward-Z surrogate: far/background=1, hand/near=0.
        // A later GLSL discard cancels this attachment write together with the pack outputs.
        String assignment = "\n    " + COVERAGE_OUTPUT + " = 0.0;";
        return source.substring(0, declarationAt)
                + declaration
                + source.substring(declarationAt, bodyAt)
                + assignment
                + source.substring(bodyAt);
    }

    private static @Nullable MetalGpuTexture exactCoverageForCurrentFrame() {
        IrisMetalPipelineOverrides.Instance instance = IrisMetalPipelineOverrides.active();
        if (instance == null || owner != instance || readyFrameSerial != frameSerial) {
            return null;
        }
        return coverageTexture;
    }

    private static void ensureCoverageTarget(
            final MetalDevice device,
            final IrisMetalPipelineOverrides.Instance instance,
            final int width,
            final int height
    ) {
        if (owner == instance && coverageTexture != null
                && coverageWidth == width && coverageHeight == height) {
            return;
        }
        releaseCoverageTarget();
        owner = instance;
        coverageWidth = width;
        coverageHeight = height;
        coverageTexture = (MetalGpuTexture) device.createTexture(
                "iris-metalfx-hand-coverage",
                COVERAGE_USAGE,
                GpuFormat.R8_UNORM,
                width,
                height,
                1,
                1
        );
        coverageView = new MetalGpuTextureView(coverageTexture, 0, 1);
    }

    private static MetalGpuTexture requireCoverageTexture() {
        MetalGpuTexture texture = coverageTexture;
        if (texture == null) {
            throw new IllegalStateException("Iris hand coverage texture is not allocated");
        }
        return texture;
    }

    private static void releaseCoverageTarget() {
        if (coverageView != null) {
            coverageView.close();
            coverageView = null;
        }
        if (coverageTexture != null) {
            coverageTexture.close();
            coverageTexture = null;
        }
        coverageWidth = 0;
        coverageHeight = 0;
    }

    private static @Nullable IrisMetalRenderTargets renderTargets(
            final IrisMetalPipelineOverrides.Instance instance
    ) {
        return get(RENDER_TARGETS, instance, IrisMetalRenderTargets.class);
    }

    private static Field instanceField(final Class<?> ownerClass, final String name) {
        try {
            Field field = ownerClass.getDeclaredField(name);
            if (!field.trySetAccessible()) {
                throw new IllegalStateException(ownerClass.getName() + "." + name + " is not accessible");
            }
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Metal runtime ABI changed: expected " + ownerClass.getName() + "." + name,
                    e
            );
        }
    }

    private static Field staticField(final Class<?> ownerClass, final String name) {
        return instanceField(ownerClass, name);
    }

    private static @Nullable Object getStatic(final Field field) {
        try {
            return field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read " + field, e);
        }
    }

    private static <T> @Nullable T get(final Field field, final Object receiver, final Class<T> type) {
        try {
            Object value = field.get(receiver);
            return value == null ? null : type.cast(value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read " + field, e);
        }
    }

    private static int getInt(final Field field, final Object receiver) {
        try {
            return field.getInt(receiver);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read " + field, e);
        }
    }

    private static boolean getBoolean(final Field field, final Object receiver) {
        try {
            return field.getBoolean(receiver);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read " + field, e);
        }
    }

    private static float getFloat(final Field field, final @Nullable Object receiver) {
        try {
            return field.getFloat(receiver);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read " + field, e);
        }
    }

    private record SyntheticBuild(int location) {
    }
}
