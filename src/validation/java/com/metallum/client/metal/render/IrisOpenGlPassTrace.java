package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.validation.contract.SemanticPassIdResolver;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validation-only OpenGL pass observer used as the reference side of Iris/Metal
 * correctness runs.
 *
 * <p>This class deliberately lives in the validation source set. It never
 * changes OpenGL state and performs no work unless both {@code metallum.iris.trace}
 * and {@code metallum.iris.openglTrace} are enabled. Iris-private implementation
 * details are read reflectively so a missing diagnostic field degrades the
 * evidence instead of changing renderer behavior.</p>
 */
public final class IrisOpenGlPassTrace {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.iris.trace")
            && Boolean.getBoolean("metallum.iris.openglTrace");
    private static final Object LOCK = new Object();
    private static final ThreadLocal<Deque<Context>> CONTEXTS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<LogicalPass> LOGICAL_PASS = new ThreadLocal<>();
    private static final Map<RenderPass, RenderPassState> RENDER_PASSES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private static BufferedWriter writer;
    private static boolean writerInitialized;
    private static boolean shutdownHookInstalled;
    private static long sequence;

    private IrisOpenGlPassTrace() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void beginGroup(final Object renderer, final String stage) {
        if (!ENABLED) return;
        finishLogicalPass();
        String normalized = normalizeStage(stage);
        CONTEXTS.get().push(new Context(normalized, groupSemantic(normalized), false));
        write("group", fields(
                "phase", "begin",
                "stage", normalized,
                "semanticPassId", groupSemantic(normalized),
                "rendererClass", renderer == null ? null : renderer.getClass().getName(),
                "plannedPassCount", sizeOf(field(renderer, "passes"))
        ));
    }

    public static void endGroup(final String stage) {
        if (!ENABLED) return;
        finishLogicalPass();
        String normalized = normalizeStage(stage);
        Deque<Context> contexts = CONTEXTS.get();
        if (!contexts.isEmpty() && !contexts.peek().logical()) contexts.pop();
        write("group", fields(
                "phase", "end",
                "stage", normalized,
                "semanticPassId", groupSemantic(normalized)
        ));
    }

    public static void beginTerrain(final Object terrainPass, final Object activeProgram) {
        if (!ENABLED) return;
        finishLogicalPass();
        CONTEXTS.get().push(new Context("gbuffers", "iris/gbuffers/terrain", false));
        write("terrain-context", fields(
                "phase", "begin",
                "semanticPassId", "iris/gbuffers/terrain",
                "terrainPass", stringValue(terrainPass),
                "pipeline", pipelineFields(activeProgram)
        ));
    }

    public static void endTerrain() {
        if (!ENABLED) return;
        finishLogicalPass();
        popContext("iris/gbuffers/terrain");
        write("terrain-context", fields(
                "phase", "end",
                "semanticPassId", "iris/gbuffers/terrain"
        ));
    }

    public static void beginFinalPass(final Object renderer) {
        if (!ENABLED) return;
        beginGroup(renderer, "final");
        Object finalPass = field(renderer, "finalPass");
        if (finalPass != null) beginLogicalPass(finalPass, "final");
    }

    public static void endFinalPass() {
        if (!ENABLED) return;
        finishLogicalPass();
        endGroup("final");
    }

    public static void beginLogicalPass(final Object pass, final String stageHint) {
        if (!ENABLED || pass == null) return;
        finishLogicalPass();
        String stage = normalizeStage(stageHint == null ? currentStage() : stageHint);
        String name = stringField(pass, "name", pass.getClass().getSimpleName());
        String semantic = semanticPass(stage, name);
        CONTEXTS.get().push(new Context(stage, semantic, true));
        LogicalPass state = new LogicalPass(stage, semantic, name, pass.getClass().getName());
        LOGICAL_PASS.set(state);
        write("logical-pass", fields(
                "phase", "begin",
                "stage", stage,
                "semanticPassId", semantic,
                "passName", name,
                "passClass", pass.getClass().getName(),
                "drawBuffers", intList(field(pass, "drawBuffers")),
                "program", programFields(field(pass, "program")),
                "framebuffer", framebufferFields(field(pass, "framebuffer")),
                "loadStoreEvidence", "unavailable-at-raw-opengl-fbo-boundary"
        ));
    }

    public static void logicalDraw() {
        if (!ENABLED) return;
        LogicalPass state = LOGICAL_PASS.get();
        if (state != null) state.drawCalls++;
    }

    public static void finishLogicalPass() {
        if (!ENABLED) return;
        LogicalPass state = LOGICAL_PASS.get();
        if (state == null) return;
        write("logical-pass", fields(
                "phase", "end",
                "stage", state.stage,
                "semanticPassId", state.semanticPassId,
                "passName", state.passName,
                "passClass", state.passClass,
                "drawCalls", state.drawCalls
        ));
        LOGICAL_PASS.remove();
        popContext(state.semanticPassId);
    }

    public static void createdRenderPass(
            final RenderPass renderPass,
            final RenderPassDescriptor descriptor
    ) {
        if (!ENABLED || renderPass == null || descriptor == null) return;
        String rawLabel = descriptorLabel(descriptor);
        String semantic = currentSemantic();
        if (semantic == null || semantic.isBlank()) {
            semantic = SemanticPassIdResolver.resolve(rawLabel);
        }
        RenderPassState state = new RenderPassState(semantic, rawLabel);
        RENDER_PASSES.put(renderPass, state);
        write("render-pass", fields(
                "phase", "begin",
                "semanticPassId", semantic,
                "rawLabel", rawLabel,
                "passClass", renderPass.getClass().getName(),
                "colorAttachments", attachmentFields(field(descriptor, "colorAttachments")),
                "depthAttachment", attachmentFields(field(descriptor, "depthAttachment")),
                "renderArea", stringValue(field(descriptor, "renderArea")),
                "loadStoreEvidence", "descriptor-clear-values-and-implicit-store"
        ));
    }

    public static void pipeline(final RenderPass renderPass, final RenderPipeline pipeline) {
        if (!ENABLED) return;
        RenderPassState state = RENDER_PASSES.get(renderPass);
        if (state == null) return;
        state.pipeline = pipelineFields(pipeline);
        write("render-pass-pipeline", fields(
                "semanticPassId", state.semanticPassId,
                "rawLabel", state.rawLabel,
                "pipeline", state.pipeline
        ));
    }

    public static void bindTexture(
            final RenderPass renderPass,
            final String name,
            final GpuTextureView view
    ) {
        if (!ENABLED) return;
        RenderPassState state = RENDER_PASSES.get(renderPass);
        if (state == null) return;
        Map<String, Object> binding = fields(
                "name", name,
                "texture", textureViewFields(view)
        );
        state.sampledResources.add(binding);
        write("render-pass-bind", fields(
                "semanticPassId", state.semanticPassId,
                "rawLabel", state.rawLabel,
                "binding", binding
        ));
    }

    public static void renderPassDraw(final RenderPass renderPass) {
        if (!ENABLED) return;
        RenderPassState state = RENDER_PASSES.get(renderPass);
        if (state != null) state.drawCalls++;
    }

    public static void closedRenderPass(final RenderPass renderPass) {
        if (!ENABLED) return;
        RenderPassState state = RENDER_PASSES.remove(renderPass);
        if (state == null) return;
        write("render-pass", fields(
                "phase", "end",
                "semanticPassId", state.semanticPassId,
                "rawLabel", state.rawLabel,
                "pipeline", state.pipeline,
                "sampledResources", state.sampledResources,
                "drawCalls", state.drawCalls
        ));
    }

    public static void close() {
        synchronized (LOCK) {
            if (writer == null) return;
            try {
                writer.close();
            } catch (IOException ignored) {
            } finally {
                writer = null;
            }
        }
    }

    private static String semanticPass(final String stage, final String name) {
        if ("final".equals(stage)) return "iris/final";
        String label = "Iris " + stage + " " + name;
        return SemanticPassIdResolver.resolve(label);
    }

    private static String groupSemantic(final String stage) {
        if ("final".equals(stage)) return "iris/final";
        if ("shadowcomp".equals(stage)) return "iris/shadow";
        return "iris/" + stage;
    }

    private static String normalizeStage(final String value) {
        if (value == null || value.isBlank()) return "unclassified";
        String lower = value.toLowerCase(Locale.ROOT).replace('-', '_');
        if (lower.startsWith("deferred")) return "deferred";
        if (lower.startsWith("composite")) return "composite";
        if (lower.startsWith("prepare")) return "prepare";
        if (lower.startsWith("begin")) return "begin";
        if (lower.startsWith("shadow")) return "shadowcomp";
        if (lower.startsWith("final")) return "final";
        return lower.replaceAll("[^a-z0-9_]+", "-");
    }

    private static String currentStage() {
        Deque<Context> contexts = CONTEXTS.get();
        return contexts.isEmpty() ? "unclassified" : contexts.peek().stage();
    }

    private static String currentSemantic() {
        Deque<Context> contexts = CONTEXTS.get();
        return contexts.isEmpty() ? null : contexts.peek().semanticPassId();
    }

    private static void popContext(final String expected) {
        Deque<Context> contexts = CONTEXTS.get();
        if (!contexts.isEmpty() && expected.equals(contexts.peek().semanticPassId())) contexts.pop();
    }

    private static String descriptorLabel(final RenderPassDescriptor descriptor) {
        Object label = field(descriptor, "label");
        if (label instanceof java.util.function.Supplier<?> supplier) {
            try {
                Object value = supplier.get();
                return value == null ? "" : String.valueOf(value);
            } catch (RuntimeException ignored) {
                return "";
            }
        }
        return label == null ? "" : String.valueOf(label);
    }

    private static Map<String, Object> pipelineFields(final Object pipeline) {
        if (pipeline == null) return Map.of("present", false);
        return fields(
                "present", true,
                "class", pipeline.getClass().getName(),
                "identity", stringValue(invokeNoArg(pipeline, "getLocation"))
        );
    }

    private static Map<String, Object> programFields(final Object program) {
        if (program == null) return Map.of("present", false);
        return fields(
                "present", true,
                "class", program.getClass().getName(),
                "programId", scalar(field(program, "program"))
        );
    }

    private static Object framebufferFields(final Object framebuffer) {
        if (framebuffer == null) return Map.of("present", false);
        return fields(
                "present", true,
                "class", framebuffer.getClass().getName(),
                "framebufferId", scalar(field(framebuffer, "id"))
        );
    }

    private static Object attachmentFields(final Object value) {
        if (value == null) return null;
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>(collection.size());
            for (Object item : collection) result.add(singleAttachment(item));
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>(Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) result.add(singleAttachment(Array.get(value, i)));
            return result;
        }
        return singleAttachment(value);
    }

    private static Object singleAttachment(final Object attachment) {
        if (attachment == null) return null;
        Object view = field(attachment, "textureView");
        Object clear = field(attachment, "clearValue");
        return fields(
                "class", attachment.getClass().getName(),
                "texture", view instanceof GpuTextureView textureView ? textureViewFields(textureView) : stringValue(view),
                "clearValue", stringValue(clear)
        );
    }

    private static Map<String, Object> textureViewFields(final GpuTextureView view) {
        if (view == null) return Map.of("present", false);
        Object texture = view.texture();
        return fields(
                "present", true,
                "viewClass", view.getClass().getName(),
                "textureClass", texture == null ? null : texture.getClass().getName(),
                "texture", stringValue(texture),
                "mip", scalar(invokeNoArg(view, "baseMipLevel"))
        );
    }

    private static List<Integer> intList(final Object value) {
        if (!(value instanceof int[] ints)) return List.of();
        List<Integer> result = new ArrayList<>(ints.length);
        for (int item : ints) result.add(item);
        return result;
    }

    private static int sizeOf(final Object value) {
        if (value == null) return 0;
        if (value instanceof Collection<?> collection) return collection.size();
        if (value.getClass().isArray()) return Array.getLength(value);
        return -1;
    }

    private static Object scalar(final Object value) {
        return value instanceof Number || value instanceof Boolean || value instanceof String ? value : stringValue(value);
    }

    private static String stringField(final Object target, final String name, final String fallback) {
        Object value = field(target, name);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String stringValue(final Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Object invokeNoArg(final Object target, final String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object field(final Object target, final String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, Object> fields(final Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) result.put(String.valueOf(entries[i]), entries[i + 1]);
        return result;
    }

    private static void write(final String type, final Map<String, ?> values) {
        if (!ENABLED) return;
        synchronized (LOCK) {
            ensureWriter();
            if (writer == null) return;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("schema", 1);
            event.put("type", type);
            event.put("source", "opengl");
            event.put("sequence", sequence++);
            event.putAll(values);
            try {
                writer.write(json(event));
                writer.newLine();
                writer.flush();
            } catch (IOException failure) {
                Metallum.LOGGER.warn("[metallum-iris-trace] could not write OpenGL pass trace", failure);
                close();
            }
        }
    }

    private static void ensureWriter() {
        if (writerInitialized) return;
        writerInitialized = true;
        String configured = System.getProperty(
                "metallum.iris.openglPassTracePath",
                "run/metallum-iris/opengl-pass-trace.jsonl"
        );
        try {
            Path path = Path.of(configured);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            writer = Files.newBufferedWriter(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            if (!shutdownHookInstalled) {
                shutdownHookInstalled = true;
                Runtime.getRuntime().addShutdownHook(
                        new Thread(IrisOpenGlPassTrace::close, "metallum-opengl-pass-trace-close")
                );
            }
        } catch (IOException | RuntimeException failure) {
            Metallum.LOGGER.warn("[metallum-iris-trace] OpenGL pass trace disabled: {}", configured, failure);
        }
    }

    private static String json(final Map<String, ?> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(escape(entry.getKey())).append("\":");
            appendJson(out, entry.getValue());
        }
        return out.append('}').toString();
    }

    private static void appendJson(final StringBuilder out, final Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            out.append(json(normalized));
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                appendJson(out, item);
            }
            out.append(']');
        } else {
            out.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private record Context(String stage, String semanticPassId, boolean logical) {
    }

    private static final class LogicalPass {
        private final String stage;
        private final String semanticPassId;
        private final String passName;
        private final String passClass;
        private int drawCalls;

        private LogicalPass(
                final String stage,
                final String semanticPassId,
                final String passName,
                final String passClass
        ) {
            this.stage = stage;
            this.semanticPassId = semanticPassId;
            this.passName = passName;
            this.passClass = passClass;
        }
    }

    private static final class RenderPassState {
        private final String semanticPassId;
        private final String rawLabel;
        private final List<Map<String, Object>> sampledResources = new ArrayList<>();
        private Map<String, Object> pipeline = Map.of("present", false);
        private int drawCalls;

        private RenderPassState(final String semanticPassId, final String rawLabel) {
            this.semanticPassId = semanticPassId;
            this.rawLabel = rawLabel;
        }
    }
}
