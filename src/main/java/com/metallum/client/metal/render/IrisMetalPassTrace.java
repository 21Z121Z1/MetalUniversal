package com.metallum.client.metal.render;

import com.metallum.Metallum;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified Iris/Vulkan-oracle versus Metal execution trace.
 *
 * <p>The oracle is derived from the same Iris {@link ProgramSet} and mirrors
 * Iris's {@code CompositeRenderer} construction rule: a pass samples the
 * buffer side captured before the pass, then flips every DRAWBUFFERS target
 * unless an explicit flip disables it. The trace deliberately labels this as
 * an Iris reference, not as raw Vulkan bytes; backend-specific handles and
 * formats are recorded separately by the Metal events.</p>
 */
final class IrisMetalPassTrace {
    private static final Pattern UNIFORM = Pattern.compile(
            "(?m)\\buniform\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;"
    );
    private static final Pattern INT_DEFINE = Pattern.compile(
            "(?m)^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(-?\\d+)\\b"
    );
    private static final Pattern FRAME_COUNTER_MOD_2 = Pattern.compile(
            "\\bframeCounter\\s*%\\s*2\\b"
    );
    private static final Pattern FRAME_COUNTER_MOD_8 = Pattern.compile(
            "\\bframemod8\\b|\\bframeCounter\\s*%\\s*8\\b"
    );
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("metallum.iris.trace", "false")
    );
    private static final Object LOCK = new Object();
    private static @Nullable Session active;

    private IrisMetalPassTrace() {
    }

    static void activate(final ProgramSet programSet, final int generation) {
        if (!ENABLED) {
            return;
        }
        synchronized (LOCK) {
            closeLocked();
            Session session = new Session(generation, oracle(programSet));
            active = session;
            session.writeEvent("session", Map.of(
                    "status", "start",
                    "oracle", "iris-vulkan-reference",
                    "generation", generation,
                    "pack", programSet.getPack().getProfileInfo().toString()
            ));
            for (OraclePass pass : session.oracle) {
                session.writeEvent("oracle-pass", pass.fields(generation));
            }
        }
    }

    static void beginFrame(final int frameCounter) {
        Session session = active;
        if (session == null) {
            return;
        }
        synchronized (LOCK) {
            if (active != session) {
                return;
            }
            session.frame = frameCounter;
            // This event must describe the uniforms actually uploaded by the
            // Metal path. A hard-coded two-phase jitter used to mislabel BSL's
            // TAA_MODE=0 as TAA_MODE=1.
            session.writeEvent("frame", Map.of(
                    "source", "metal",
                    "frameCounter", frameCounter,
                    "framemod8", frameCounter % 8,
                    "framemod2", frameCounter % 2,
                    "oracleJitterRules", session.oracleJitterRules()
            ));
        }
    }

    static void observePhase(final String phase, final String status) {
        writeFrameScoped("phase", phase + "|" + status, Map.of("phase", phase, "status", status));
    }

    static void observeTerrain(final String kind, final int[] drawBuffers) {
        writeFrameScoped("terrain", kind + "|" + Arrays.toString(drawBuffers), Map.of(
                "kind", kind,
                "drawBuffers", ints(drawBuffers),
                "status", "observed"
        ));
    }

    static void observeTerrainPath(
            final String kind,
            final int[] drawBuffers,
            final int attachmentCount,
            final String status
    ) {
        writeFrameScoped("terrain-pass", kind + "|" + status, Map.of(
                "kind", kind,
                "drawBuffers", ints(drawBuffers),
                "attachmentCount", attachmentCount,
                "status", status
        ));
    }

    static void observeTerrainPipeline(
            final String kind,
            final int[] drawBuffers,
            final String originalPipeline,
            final String selectedPipeline,
            final String status,
            final boolean synthetic
    ) {
        writeFrameScoped("terrain-pipeline", kind + "|" + originalPipeline + "|" + status, Map.of(
                "kind", kind,
                "drawBuffers", ints(drawBuffers),
                "originalPipeline", originalPipeline,
                "selectedPipeline", selectedPipeline,
                "status", status,
                "synthetic", synthetic
        ));
    }

    static void observeDepth(final String name) {
        writeFrameScoped("depth", name, Map.of("name", name, "status", "observed"));
    }

    static void observeTargets(
            final String status,
            final int width,
            final int height,
            final int count,
            final String formats
    ) {
        writeMetal("targets", Map.of(
                "status", status,
                "width", width,
                "height", height,
                "count", count,
                "formats", formats
        ));
    }

    static void observeSampler(final String name, final String source) {
        Session session = active;
        if (session == null) {
            return;
        }
        synchronized (LOCK) {
            if (active == session && session.samplerKeys.add(name + "|" + source)) {
                Map<String, Object> event = new HashMap<>();
                event.put("source", "metal");
                event.put("frameCounter", session.frame);
                event.put("name", name);
                event.put("sourceName", source);
                session.writeEvent("sampler", event);
            }
        }
    }

    static void markMissing(final String stage) {
        writeFrameScoped("stage", stage + "|missing", Map.of("stage", stage, "status", "missing"));
    }

    static void close() {
        if (!ENABLED) {
            return;
        }
        synchronized (LOCK) {
            closeLocked();
        }
    }

    private static void writeMetal(final String type, final Map<String, ?> fields) {
        Session session = active;
        if (session == null) {
            return;
        }
        synchronized (LOCK) {
            if (active == session) {
                Map<String, Object> event = new HashMap<>();
                event.put("source", "metal");
                event.put("frameCounter", session.frame);
                event.putAll(fields);
                session.writeEvent(type, event);
            }
        }
    }

    private static void writeFrameScoped(
            final String type,
            final String key,
            final Map<String, ?> fields
    ) {
        Session session = active;
        if (session == null) {
            return;
        }
        synchronized (LOCK) {
            if (active == session && session.frameKeys.add(type + "|" + session.frame + "|" + key)) {
                Map<String, Object> event = new HashMap<>();
                event.put("source", "metal");
                event.put("frameCounter", session.frame);
                event.putAll(fields);
                session.writeEvent(type, event);
            }
        }
    }

    private static void closeLocked() {
        Session session = active;
        active = null;
        if (session != null) {
            session.writeEvent("session", Map.of("status", "end", "generation", session.generation));
            session.close();
        }
    }

    private static List<OraclePass> oracle(final ProgramSet set) {
        List<OraclePass> passes = new ArrayList<>();
        PackDirectives directives = set.getPackDirectives();
        Set<Integer> flipped = new TreeSet<>();

        addCompositeArray(passes, set, ProgramArrayId.Setup, TextureStage.SETUP, flipped, 0);
        addPreFlips(flipped, directives, "begin_pre");
        addCompositeArray(passes, set, ProgramArrayId.Begin, TextureStage.BEGIN, flipped, 0);
        addProgram(passes, set, ProgramId.Shadow, "shadow", 500);
        addProgram(passes, set, ProgramId.ShadowSolid, "shadow", 501);
        addProgram(passes, set, ProgramId.ShadowCutout, "shadow", 502);
        addProgram(passes, set, ProgramId.ShadowWater, "shadow", 503);
        addProgram(passes, set, ProgramId.ShadowEntities, "shadow", 504);
        addProgram(passes, set, ProgramId.ShadowLightning, "shadow", 505);
        addProgram(passes, set, ProgramId.ShadowBlock, "shadow", 506);
        addCompositeArray(passes, set, ProgramArrayId.ShadowComposite, TextureStage.SHADOWCOMP, flipped, 700);
        addComputeGroup(passes, set.getShadowCompute(), "shadowcomp", 800);
        addPreFlips(flipped, directives, "prepare_pre");
        addCompositeArray(passes, set, ProgramArrayId.Prepare, TextureStage.PREPARE, flipped, 1000);
        addGbufferPrograms(passes, set, flipped, 2000);
        addPreFlips(flipped, directives, "deferred_pre");
        addCompositeArray(passes, set, ProgramArrayId.Deferred, TextureStage.DEFERRED, flipped, 3000);
        addPreFlips(flipped, directives, "composite_pre");
        addCompositeArray(passes, set, ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, flipped, 4000);
        addProgram(passes, set, ProgramId.Final, "final", 5000);
        addComputeGroup(passes, set.getFinalCompute(), "final", 5100);

        passes.sort(Comparator.comparingInt(OraclePass::order));
        return passes;
    }

    private static void addGbufferPrograms(
            final List<OraclePass> destination,
            final ProgramSet set,
            final Set<Integer> flipped,
            final int orderBase
    ) {
        ProgramId[] ids = {
                ProgramId.Basic, ProgramId.Line, ProgramId.Textured, ProgramId.TexturedLit,
                ProgramId.SkyBasic, ProgramId.SkyTextured, ProgramId.Clouds, ProgramId.Terrain,
                ProgramId.TerrainSolid, ProgramId.TerrainCutout, ProgramId.DamagedBlock,
                ProgramId.Block, ProgramId.BlockTrans, ProgramId.BeaconBeam, ProgramId.Item,
                ProgramId.Entities, ProgramId.EntitiesTrans, ProgramId.Lightning,
                ProgramId.Particles, ProgramId.ParticlesTrans, ProgramId.EntitiesGlowing,
                ProgramId.ArmorGlint, ProgramId.SpiderEyes, ProgramId.Hand, ProgramId.Weather,
                ProgramId.Water, ProgramId.HandWater
        };
        for (int index = 0; index < ids.length; index++) {
            addProgram(destination, set, ids[index], "gbuffer", orderBase + index);
        }
    }

    private static void addProgram(
            final List<OraclePass> destination,
            final ProgramSet set,
            final ProgramId id,
            final String stage,
            final int order
    ) {
        java.util.Optional<ProgramSource> source = set.get(id);
        if (source.isPresent() && source.get().isValid()) {
            destination.add(OraclePass.fromSource(stage, source.get(), new TreeSet<>(), order));
        }
    }

    private static void addCompositeArray(
            final List<OraclePass> destination,
            final ProgramSet set,
            final ProgramArrayId arrayId,
            final TextureStage stage,
            final Set<Integer> flipped,
            final int orderBase
    ) {
        ProgramSource[] sources = set.getComposite(arrayId);
        for (int index = 0; index < sources.length; index++) {
            ProgramSource source = sources[index];
            if (source == null || !source.isValid()) {
                continue;
            }
            Set<Integer> before = new TreeSet<>(flipped);
            OraclePass pass = OraclePass.fromSource(
                    stageName(stage), source, before, orderBase + index
            );
            destination.add(pass);
            applyFlips(flipped, source.getDirectives().getDrawBuffers(), source.getDirectives().getExplicitFlips());
        }
        ComputeSource[][] computes = set.getCompute(arrayId);
        for (int index = 0; index < computes.length; index++) {
            ComputeSource[] group = computes[index];
            if (group == null) {
                continue;
            }
            for (ComputeSource source : group) {
                if (source != null && source.isValid() && source.getSource().isPresent()) {
                    destination.add(OraclePass.fromCompute(
                            stageName(stage), source, new TreeSet<>(flipped), orderBase + 100 + index
                    ));
                }
            }
        }
    }

    private static void addComputeGroup(
            final List<OraclePass> destination,
            final ComputeSource[] sources,
            final String stage,
            final int orderBase
    ) {
        if (sources == null) {
            return;
        }
        for (int index = 0; index < sources.length; index++) {
            ComputeSource source = sources[index];
            if (source != null && source.isValid() && source.getSource().isPresent()) {
                destination.add(OraclePass.fromCompute(stage, source, new TreeSet<>(), orderBase + index));
            }
        }
    }

    private static void addPreFlips(final Set<Integer> flipped, final PackDirectives directives, final String key) {
        Map<Integer, Boolean> preFlips = directives.getExplicitFlips(key);
        for (Map.Entry<Integer, Boolean> entry : preFlips.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                toggle(flipped, entry.getKey());
            }
        }
    }

    private static void applyFlips(
            final Set<Integer> flipped,
            final int[] drawBuffers,
            final Map<Integer, Boolean> explicitFlips
    ) {
        for (int buffer : drawBuffers) {
            if (explicitFlips.get(buffer) != Boolean.FALSE) {
                toggle(flipped, buffer);
            }
        }
        for (Map.Entry<Integer, Boolean> entry : explicitFlips.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                toggle(flipped, entry.getKey());
            }
        }
    }

    private static void toggle(final Set<Integer> flipped, final int target) {
        if (!flipped.remove(target)) {
            flipped.add(target);
        }
    }

    private static String stageName(final TextureStage stage) {
        return switch (stage) {
            case BEGIN -> "begin";
            case PREPARE -> "prepare";
            case DEFERRED -> "deferred";
            case SHADOWCOMP -> "shadowcomp";
            case SETUP -> "setup";
            case COMPOSITE_AND_FINAL -> "composite";
            case GBUFFERS_AND_SHADOW -> "gbuffer";
            default -> stage.name().toLowerCase(Locale.ROOT);
        };
    }

    private static List<Integer> ints(final int[] values) {
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) {
            result.add(value);
        }
        return result;
    }

    private static List<String> samplers(final String... sources) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String source : sources) {
            if (source == null) {
                continue;
            }
            Matcher matcher = UNIFORM.matcher(source);
            while (matcher.find()) {
                String type = matcher.group(1).toLowerCase(Locale.ROOT);
                if (type.contains("sampler") || type.contains("image")) {
                    names.add(matcher.group(2));
                }
            }
        }
        return List.copyOf(names);
    }

    /** Returns the source-backed jitter rule without claiming a runtime pixel offset. */
    static String jitterRuleFor(final String programName, final String source) {
        if (programName.equalsIgnoreCase("composite7")) {
            Integer taaMode = definedInt(source, "TAA_MODE");
            if (taaMode != null && taaMode == 0) {
                return "none";
            }
            if (FRAME_COUNTER_MOD_2.matcher(source).find()) {
                return taaMode != null && taaMode == 1
                        ? "framemod2=frameCounter%2;offset=(0.5,0)/(0,0.5)"
                        : "unknown:frameCounter%2 (TAA_MODE not proven)";
            }
            return "none";
        }
        if (programName.equalsIgnoreCase("gbuffers_terrain")
                && FRAME_COUNTER_MOD_8.matcher(source).find()
                && source.contains("jitterOffsets8")) {
            return "framemod8=frameCounter%8;jitterOffsets8";
        }
        return "none";
    }

    private static @Nullable Integer definedInt(final String source, final String name) {
        Matcher matcher = INT_DEFINE.matcher(source);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                try {
                    return Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String source(final java.util.Optional<String> value) {
        return value.orElse("");
    }

    private record OraclePass(
            String stage,
            String name,
            List<String> reads,
            List<Integer> writes,
            List<Integer> flipBefore,
            List<Integer> flipAfter,
            String jitterRule,
            int order
    ) {
        static OraclePass fromSource(
                final String stage,
                final ProgramSource source,
                final Set<Integer> flipBefore,
                final int order
        ) {
            int[] writes = source.getDirectives().getDrawBuffers();
            Set<Integer> after = new TreeSet<>(flipBefore);
            applyFlips(after, writes, source.getDirectives().getExplicitFlips());
            String vertex = source(source.getVertexSource());
            String fragment = source(source.getFragmentSource());
            String jitter = jitterRuleFor(source.getName(), vertex + "\n" + fragment);
            return new OraclePass(
                    stage,
                    source.getName(),
                    samplers(vertex, fragment),
                    ints(writes),
                    new ArrayList<>(flipBefore),
                    new ArrayList<>(after),
                    jitter,
                    order
            );
        }

        static OraclePass fromCompute(
                final String stage,
                final ComputeSource source,
                final Set<Integer> flipBefore,
                final int order
        ) {
            return new OraclePass(
                    stage,
                    source.getName(),
                    samplers(source(source.getSource())),
                    List.of(),
                    new ArrayList<>(flipBefore),
                    new ArrayList<>(flipBefore),
                    "none",
                    order
            );
        }

        Map<String, Object> fields(final int generation) {
            return Map.of(
                    "source", "iris-vulkan-reference",
                    "generation", generation,
                    "stage", stage,
                    "pass", name,
                    "reads", reads,
                    "writes", writes,
                    "flipBefore", flipBefore,
                    "flipAfter", flipAfter,
                    "jitterRule", jitterRule,
                    "order", order
            );
        }
    }

    private static final class Session implements AutoCloseable {
        private final int generation;
        private final List<OraclePass> oracle;
        private final @Nullable BufferedWriter writer;
        private final Set<String> warned = new HashSet<>();
        private final Set<String> frameKeys = new HashSet<>();
        private final Set<String> samplerKeys = new HashSet<>();
        private int frame = -1;

        private Session(final int generation, final List<OraclePass> oracle) {
            this.generation = generation;
            this.oracle = oracle;
            this.writer = openWriter();
        }

        private void writeEvent(final String type, final Map<String, ?> fields) {
            Map<String, Object> event = new HashMap<>();
            event.put("schema", 1);
            event.put("type", type);
            event.putAll(fields);
            String json = json(event);
            Metallum.LOGGER.info("[metallum-iris-trace] {}", json);
            if (writer == null) {
                return;
            }
            try {
                writer.write(json);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                if (warned.add("write")) {
                    Metallum.LOGGER.warn("[metallum-iris-trace] could not write pass trace", e);
                }
            }
        }

        private List<String> oracleJitterRules() {
            LinkedHashSet<String> rules = new LinkedHashSet<>();
            for (OraclePass pass : oracle) {
                if (!pass.jitterRule().equals("none")) {
                    rules.add(pass.stage() + ":" + pass.name() + ":" + pass.jitterRule());
                }
            }
            return List.copyOf(rules);
        }

        private static @Nullable BufferedWriter openWriter() {
            String configured = System.getProperty("metallum.iris.trace.path", "run/metallum-iris/pass-trace.jsonl");
            try {
                Path path = Path.of(configured);
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                return Files.newBufferedWriter(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            } catch (IOException | RuntimeException e) {
                Metallum.LOGGER.warn("[metallum-iris-trace] pass trace file disabled: {}", configured, e);
                return null;
            }
        }

        @Override
        public void close() {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // Diagnostic output must not affect rendering teardown.
                }
            }
        }
    }

    private static String json(final Map<String, ?> fields) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(escape(entry.getKey())).append("\":");
            appendValue(out, entry.getValue());
        }
        return out.append('}').toString();
    }

    private static void appendValue(final StringBuilder out, final Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            out.append(json(normalized));
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendValue(out, item);
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                appendValue(out, java.lang.reflect.Array.get(value, i));
            }
            out.append(']');
        } else {
            out.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String escape(final String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
