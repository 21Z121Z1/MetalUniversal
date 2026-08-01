package com.metallum.client.metal.render;

import com.metallum.Metallum;
import net.irisshaders.iris.gl.uniform.Uniform;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.fog.FogData;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Opt-in OpenGL-side uniform recorder for the fixed Iris semantic oracle. */
public final class IrisOpenGlUniformTrace {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.iris.trace")
            && Boolean.getBoolean("metallum.iris.openglTrace");
    private static final Object LOCK = new Object();
    private static final Map<Uniform, Binding> BINDINGS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ThreadLocal<Set<CachedUniform>> ACTIVE_FIXED_INPUTS = new ThreadLocal<>();
    private static @org.jspecify.annotations.Nullable BufferedWriter writer;
    private static boolean writerInitialized;

    private IrisOpenGlUniformTrace() {
    }

    public static void register(
            final Uniform uniform,
            final String programName,
            final int program,
            final @org.jspecify.annotations.Nullable String uniformName,
            final @org.jspecify.annotations.Nullable UniformType type,
            final String frequency
    ) {
        if (!ENABLED || uniformName == null || uniformName.isEmpty()) {
            return;
        }
        BINDINGS.put(
                uniform,
                new Binding(programName, program, uniformName, type == null ? "unknown" : type.name(), frequency)
        );
        ensureWriter();
    }

    public static void record(final Uniform uniform) {
        if (!ENABLED) {
            return;
        }
        Binding binding = BINDINGS.get(uniform);
        if (binding == null) {
            return;
        }
        Object cached = cachedValue(uniform);
        Map<String, Object> event = new HashMap<>();
        event.put("schema", 1);
        event.put("type", "uniform_snapshot");
        event.put("source", "opengl");
        event.put("frameCounter", frameCounter());
        event.put("program", binding.programName());
        event.put("programId", binding.program());
        event.put("uniform", binding.uniformName());
        event.put("location", uniform.getLocation());
        event.put("valueType", binding.type());
        event.put("frequency", binding.frequency());
        event.put("value", canonical(cached));
        writeEvent(event);
    }

    /** Records the real Iris ProgramUniforms.update boundary and stage membership. */
    public static void recordProgramUpdate(final ProgramUniforms programUniforms, final String phase) {
        if (!ENABLED) {
            return;
        }
        Map<String, Object> event = new HashMap<>();
        event.put("schema", 1);
        event.put("type", "program_update");
        event.put("source", "opengl");
        event.put("phase", phase);
        event.put("frameCounter", frameCounter());
        event.put("stages", programStages(programUniforms));
        writeEvent(event);
    }

    private static List<String> programStages(final ProgramUniforms programUniforms) {
        List<String> stages = new ArrayList<>();
        for (String fieldName : List.of("dynamic", "once", "perTick", "perFrame")) {
            try {
                Field field = ProgramUniforms.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(programUniforms);
                if (!(value instanceof Iterable<?> iterable)) {
                    continue;
                }
                for (Object entry : iterable) {
                    if (entry instanceof Uniform uniform) {
                        Binding binding = BINDINGS.get(uniform);
                        if (binding != null) {
                            stages.add(fieldName + ":" + binding.programName() + ":" + binding.uniformName());
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                Metallum.LOGGER.warn(
                        "[metallum-iris-trace] could not inspect ProgramUniforms." + fieldName,
                        failure
                );
            }
        }
        return stages;
    }

    /** Starts observing only the fixed inputs owned by the current Iris CustomUniforms update. */
    public static void beginFixedInputTracking(final Collection<CachedUniform> uniforms) {
        if (!ENABLED) {
            return;
        }
        Set<CachedUniform> active = Collections.newSetFromMap(new IdentityHashMap<>());
        active.addAll(uniforms);
        ACTIVE_FIXED_INPUTS.set(active);
    }

    /** Ends the current CustomUniforms fixed-input observation scope. */
    public static void endFixedInputTracking() {
        if (ENABLED) {
            ACTIVE_FIXED_INPUTS.remove();
        }
    }

    /** Records a fixed input at the same update call Iris uses for execution. */
    public static void recordFixedInputUpdate(final CachedUniform uniform) {
        if (!ENABLED) {
            return;
        }
        Set<CachedUniform> active = ACTIVE_FIXED_INPUTS.get();
        if (active == null || !active.contains(uniform)) {
            return;
        }
        recordSupplier(uniform);
    }

    private static void recordSupplier(final CachedUniform uniform) {
        // CachedUniform.update() has already executed Iris's supplier at this
        // point. Reading the committed field is essential: calling writeTo()
        // here would evaluate the expression a second time and could advance
        // stateful/history suppliers while merely observing them.
        Object cached = cachedValue(uniform);
        Map<String, Object> event = new HashMap<>();
        event.put("schema", 1);
        event.put("type", "supplier_snapshot");
        event.put("source", "iris-supplier");
        event.put("frameCounter", frameCounter());
        event.put("uniform", uniform.getName());
        event.put("valueType", String.valueOf(uniform.getType()));
        event.put("frequency", uniform.getUpdateFrequency().name());
        String externalInput = externalInputKind(uniform.getName());
        if (externalInput != null) {
            event.put("externalInput", externalInput);
        }
        event.put("value", canonical(cached));
        writeEvent(event);
    }


    /**
     * Returns the fixed-Iris input contract for values that are intentionally
     * sourced from outside the deterministic render timeline. Both trace
     * recorders use this helper so the source classification is backend-neutral.
     */
    static @org.jspecify.annotations.Nullable String externalInputKind(final String uniformName) {
        return switch (uniformName) {
            case "currentDate", "currentTime", "currentYearTime" -> "wall_clock_local_date_time";
            default -> null;
        };
    }

    /** Records an OpenGL-side lifecycle boundary alongside uniform snapshots. */
    public static void recordLifecycle(final String phase, final Map<String, ?> fields) {
        if (!ENABLED) {
            return;
        }
        Map<String, Object> event = new HashMap<>();
        event.put("schema", 1);
        event.put("type", "lifecycle");
        event.put("source", "opengl");
        event.put("frameCounter", frameCounter());
        event.put("phase", phase);
        event.putAll(fields);
        ensureWriter();
        writeEvent(event);
    }

    /** Captures the fog result and Sodium's storage at FogRenderer return. */
    public static void recordFogSetup(final FogData data, final FogParameters stored) {
        Vector4f color = data.color == null ? null : new Vector4f(data.color);
        Map<String, Object> fields = new HashMap<>();
        fields.put("environmentalStart", data.environmentalStart);
        fields.put("environmentalEnd", data.environmentalEnd);
        fields.put("renderDistanceStart", data.renderDistanceStart);
        fields.put("renderDistanceEnd", data.renderDistanceEnd);
        fields.put("fogDataColor", canonical(color));
        fields.put("storedIsNone", stored == FogParameters.NONE);
        fields.put(
                "storedColor",
                stored == FogParameters.NONE
                        ? null
                        : List.of(stored.red(), stored.green(), stored.blue(), stored.alpha())
        );
        fields.put("storedEnvironmentalStart", stored.environmentalStart());
        fields.put("storedEnvironmentalEnd", stored.environmentalEnd());
        recordLifecycle("fog_setup_return", fields);
    }

    private static int frameCounter() {
        try {
            return SystemTimeUniforms.COUNTER.getAsInt();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static @org.jspecify.annotations.Nullable Object cachedValue(final Uniform uniform) {
        Class<?> type = uniform.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("cachedValue");
                field.setAccessible(true);
                return field.get(uniform);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException failure) {
                Metallum.LOGGER.warn("[metallum-iris-trace] could not read OpenGL uniform value", failure);
                return null;
            }
        }
        return null;
    }

    private static @org.jspecify.annotations.Nullable Object cachedValue(final CachedUniform uniform) {
        Class<?> type = uniform.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("cached");
                field.setAccessible(true);
                return field.get(uniform);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException failure) {
                Metallum.LOGGER.warn(
                        "[metallum-iris-trace] could not read Iris cached uniform value",
                        failure
                );
                return null;
            }
        }
        return null;
    }

    private static Object canonical(final @org.jspecify.annotations.Nullable Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof Vector2f vector) {
            return List.of(vector.x(), vector.y());
        }
        if (value instanceof Vector3f vector) {
            return List.of(vector.x(), vector.y(), vector.z());
        }
        if (value instanceof Vector4f vector) {
            return List.of(vector.x(), vector.y(), vector.z(), vector.w());
        }
        if (value instanceof Vector2i vector) {
            return List.of(vector.x(), vector.y());
        }
        if (value instanceof Vector3i vector) {
            return List.of(vector.x(), vector.y(), vector.z());
        }
        if (value instanceof Vector4i vector) {
            return List.of(vector.x(), vector.y(), vector.z(), vector.w());
        }
        if (value instanceof Matrix4fc matrix) {
            return List.of(
                    matrix.m00(), matrix.m01(), matrix.m02(), matrix.m03(),
                    matrix.m10(), matrix.m11(), matrix.m12(), matrix.m13(),
                    matrix.m20(), matrix.m21(), matrix.m22(), matrix.m23(),
                    matrix.m30(), matrix.m31(), matrix.m32(), matrix.m33()
            );
        }
        if (value instanceof Matrix3fc matrix) {
            return List.of(
                    matrix.m00(), matrix.m01(), matrix.m02(),
                    matrix.m10(), matrix.m11(), matrix.m12(),
                    matrix.m20(), matrix.m21(), matrix.m22()
            );
        }
        if (value instanceof FloatBuffer buffer) {
            FloatBuffer copy = buffer.duplicate();
            List<Float> result = new ArrayList<>(copy.remaining());
            while (copy.hasRemaining()) {
                result.add(copy.get());
            }
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                result.add(canonical(Array.get(value, index)));
            }
            return result;
        }
        return String.valueOf(value);
    }

    private static void ensureWriter() {
        if (writerInitialized) {
            return;
        }
        synchronized (LOCK) {
            if (writerInitialized) {
                return;
            }
            writerInitialized = true;
            String configured = System.getProperty(
                    "metallum.iris.openglTracePath",
                    "run/metallum-iris/opengl-uniform-trace.jsonl"
            );
            try {
                Path path = Path.of(configured);
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writer = Files.newBufferedWriter(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            } catch (IOException | RuntimeException failure) {
                Metallum.LOGGER.warn("[metallum-iris-trace] OpenGL uniform trace disabled: {}", configured, failure);
            }
        }
    }

    private static void writeEvent(final Map<String, ?> fields) {
        synchronized (LOCK) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(json(fields));
                writer.newLine();
                writer.flush();
            } catch (IOException failure) {
                Metallum.LOGGER.warn("[metallum-iris-trace] could not write OpenGL uniform trace", failure);
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
                writer = null;
            }
        }
    }

    private static String json(final Map<String, ?> fields) {
        StringBuilder output = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (!first) {
                output.append(',');
            }
            first = false;
            output.append('"').append(escape(entry.getKey())).append("\":");
            appendValue(output, entry.getValue());
        }
        return output.append('}').toString();
    }

    private static void appendValue(final StringBuilder output, final Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Iterable<?> iterable) {
            output.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                appendValue(output, item);
            }
            output.append(']');
        } else {
            output.append('"').append(escape(String.valueOf(value))).append('"');
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

    private record Binding(
            String programName,
            int program,
            String uniformName,
            String type,
            String frequency
    ) {
    }
}
