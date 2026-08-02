package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CommonUniforms;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix3fc;
import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Records Iris's dynamic-uniform registration and exposes the backend-neutral
 * draw values that Metal can provide without touching OpenGL state.
 *
 * <p>Iris registers these values through {@link CommonUniforms}; keeping the
 * registration as the source of truth prevents the Metal path from silently
 * growing a second, name-only catalog.  Values whose native Iris supplier
 * reads GL state are supplied by the draw context instead.</p>
 */
final class IrisMetalDynamicUniforms implements DynamicUniformHolder {
    private static final class Binding {
        private final UniformType type;
        private final Object supplier;
        private final boolean external;
        private final UniformUpdateFrequency frequency;
        private final ValueUpdateNotifier notifier;
        private long supplierCalls;
        private boolean invalidated = true;

        private Binding(
                final UniformType type,
                final Object supplier,
                final boolean external,
                final UniformUpdateFrequency frequency,
                final ValueUpdateNotifier notifier
        ) {
            this.type = type;
            this.supplier = supplier;
            this.external = external;
            this.frequency = frequency;
            this.notifier = notifier;
        }

        private void invalidate() {
            this.invalidated = true;
        }

    }

    /** Values committed at the same boundary as one Iris ProgramUniforms.update(). */
    static final class DrawSnapshot {
        private final long commitId;
        private final Map<String, Object> values;

        private DrawSnapshot(final long commitId, final Map<String, Object> values) {
            this.commitId = commitId;
            this.values = Map.copyOf(values);
        }
    }

    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Set<Binding> activeBindings = Collections.newSetFromMap(new IdentityHashMap<>());
    private final IntSupplier renderStageSource;
    private @Nullable Object activeProgram;
    private @Nullable List<MetalIrisShaderCompiler.UniformMember> activeLayout;
    private long activeCommitId;
    private @Nullable DrawSnapshot committedSnapshot;

    private IrisMetalDynamicUniforms(final IntSupplier renderStageSource) {
        this.renderStageSource = Objects.requireNonNull(renderStageSource, "renderStageSource");
    }

    static IrisMetalDynamicUniforms create(final IntSupplier renderStageSource) {
        IrisMetalDynamicUniforms result = new IrisMetalDynamicUniforms(renderStageSource);
        // PER_FRAGMENT registers the complete dynamic fog catalog. The active
        // program still decides whether a particular member is present.
        CommonUniforms.addDynamicUniforms(result, FogMode.PER_FRAGMENT);
        return result;
    }

    boolean contains(final String name) {
        return this.bindings.containsKey(name);
    }

    /**
     * Returns whether the pinned Iris registration has a backend value source
     * for this member. External registrations deliberately return false: the
     * owning draw state must provide those values (or the fixed-input graph
     * must contain the same logical name).
     */
    boolean canMaterialize(final MetalIrisShaderCompiler.UniformMember member) {
        Binding binding = this.bindings.get(member.name());
        return binding != null
                && !binding.external
                && member.arrayCount() == 0
                && compatible(member.type(), binding.type);
    }

    UniformUpdateFrequency frequency(final String name) {
        Binding binding = this.bindings.get(name);
        return binding == null ? null : binding.frequency;
    }

    long supplierCalls(final String name) {
        Binding binding = this.bindings.get(name);
        return binding == null ? 0L : binding.supplierCalls;
    }

    /**
     * Mirrors ProgramUniforms.removeListeners()/update() for the active Metal
     * program. The notifier callback only invalidates the next snapshot; it
     * never evaluates a supplier while a trace is reading committed bytes.
     */
    void beginProgram(
            final Object programToken,
            final List<MetalIrisShaderCompiler.UniformMember> layout
    ) {
        // Iris ProgramUniforms.update() is a commit boundary on every
        // Program.use(), including consecutive uses of the same Program.
        // It always removes the previous listener set before installing the
        // listeners for this use; do not short-circuit on program identity.
        for (Binding binding : this.activeBindings) {
            if (binding.notifier != null) {
                binding.notifier.setListener(null);
            }
        }
        this.activeBindings.clear();
        this.activeProgram = programToken;
        this.activeLayout = List.copyOf(layout);
        this.activeCommitId++;
        this.committedSnapshot = null;
        Set<String> names = new LinkedHashSet<>();
        for (MetalIrisShaderCompiler.UniformMember member : layout) {
            names.add(member.name());
        }
        for (String name : names) {
            Binding binding = this.bindings.get(name);
            if (binding == null || binding.external || binding.notifier == null) {
                continue;
            }
            this.activeBindings.add(binding);
            binding.notifier.setListener(binding::invalidate);
        }
    }

    /** Test/diagnostic overload for a standalone program identity. */
    void beginProgram(final List<MetalIrisShaderCompiler.UniformMember> layout) {
        beginProgram(layout, layout);
    }

    DrawSnapshot snapshot(
            final List<MetalIrisShaderCompiler.UniformMember> layout,
            final IrisMetalUniformValues.DrawUniformContext context
    ) {
        if (this.committedSnapshot != null
                && this.committedSnapshot.commitId == this.activeCommitId
                && Objects.equals(this.activeLayout, layout)
                && !hasInvalidatedBinding(layout)) {
            return this.committedSnapshot;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (MetalIrisShaderCompiler.UniformMember member : layout) {
            if (values.containsKey(member.name())) {
                continue;
            }
            Binding binding = this.bindings.get(member.name());
            if (binding == null || binding.external) {
                continue;
            }
            // Iris ProgramUniforms.update() always evaluates its dynamic list
            // at each program-use boundary. The returned snapshot is the
            // immutable commit consumed by all later trace/write operations;
            // no supplier is called while those bytes are being observed.
            Object value = evaluate(member.name(), binding, context);
            values.put(member.name(), snapshotValue(value));
            binding.invalidated = false;
        }
        DrawSnapshot snapshot = new DrawSnapshot(this.activeCommitId, values);
        this.committedSnapshot = snapshot;
        return snapshot;
    }

    private boolean hasInvalidatedBinding(
            final List<MetalIrisShaderCompiler.UniformMember> layout
    ) {
        for (MetalIrisShaderCompiler.UniformMember member : layout) {
            Binding binding = this.bindings.get(member.name());
            if (binding != null && !binding.external && binding.invalidated) {
                return true;
            }
        }
        return false;
    }

    boolean contains(final DrawSnapshot snapshot, final String name) {
        return snapshot.values.containsKey(name);
    }

    /**
     * Writes one Iris dynamic member. Returning false leaves externally
     * managed/core members to the existing production writer.
     */
    boolean write(
            final MetalIrisShaderCompiler.UniformMember member,
            final ByteBuffer destination,
            final IrisMetalUniformValues.DrawUniformContext context
    ) {
        return write(member, destination, context, snapshot(List.of(member), context));
    }

    boolean write(
            final MetalIrisShaderCompiler.UniformMember member,
            final ByteBuffer destination,
            final IrisMetalUniformValues.DrawUniformContext context,
            final DrawSnapshot snapshot
    ) {
        if (snapshot.commitId != 0L
                && (snapshot.commitId != this.activeCommitId
                || !Objects.equals(this.activeLayout, layoutFor(snapshot)))) {
            throw new IllegalStateException("Iris dynamic uniform snapshot belongs to an earlier program-use commit");
        }
        Binding binding = this.bindings.get(member.name());
        if (binding == null) {
            return false;
        }
        if (binding.external) {
            return false;
        }
        Object value = snapshot.values.get(member.name());
        if (value == null && !snapshot.values.containsKey(member.name())) {
            throw new IllegalStateException(
                    "Iris dynamic uniform snapshot is missing '" + member.name() + "'"
            );
        }
        int offset = member.offset();
        switch (member.name()) {
            case "entityId" -> {
                require(member, "int");
                requireType(binding, UniformType.INT);
                destination.putInt(offset, intValue(value));
                return true;
            }
            case "atlasSize" -> {
                require(member, "ivec2");
                requireType(binding, UniformType.VEC2I);
                Vector2i size = suppliedObject(value, member, Vector2i.class);
                destination.putInt(offset, size.x);
                destination.putInt(offset + 4, size.y);
                return true;
            }
            case "gtextureId" -> {
                require(member, "int");
                requireType(binding, UniformType.INT);
                destination.putInt(offset, intValue(value));
                return true;
            }
            case "textureReloadCount" -> {
                require(member, "int");
                requireType(binding, UniformType.INT);
                destination.putInt(offset, intValue(value));
                return true;
            }
            case "gtextureSize" -> {
                require(member, "ivec2");
                requireType(binding, UniformType.VEC2I);
                Vector2i size = suppliedObject(value, member, Vector2i.class);
                destination.putInt(offset, size.x);
                destination.putInt(offset + 4, size.y);
                return true;
            }
            case "blendFunc" -> {
                require(member, "ivec4");
                requireType(binding, UniformType.VEC4I);
                Vector4i blend = suppliedObject(value, member, Vector4i.class);
                for (int index = 0; index < 4; index++) {
                    destination.putInt(offset + index * Integer.BYTES, blend.get(index));
                }
                return true;
            }
            case "renderStage" -> {
                require(member, "int");
                requireType(binding, UniformType.INT);
                destination.putInt(offset, intValue(value));
                return true;
            }
            default -> {
                return writeRegisteredSupplier(member, destination, binding.type, value);
            }
        }
    }

    private List<MetalIrisShaderCompiler.UniformMember> layoutFor(final DrawSnapshot snapshot) {
        return this.committedSnapshot == snapshot && this.activeLayout != null
                ? this.activeLayout
                : List.of();
    }

    private Object evaluate(
            final String name,
            final Binding binding,
            final IrisMetalUniformValues.DrawUniformContext context
    ) {
        return switch (name) {
            case "entityId", "textureReloadCount" -> suppliedValue(binding);
            case "atlasSize" -> new Vector2i(context.atlasWidth(), context.atlasHeight());
            case "gtextureId" -> context.gtexture() == null
                    ? 0
                    : IrisMetalUniformValues.logicalTextureIdForDynamic(context.gtexture());
            case "gtextureSize" -> context.gtexture() == null
                    ? new Vector2i()
                    : new Vector2i(context.gtexture().getWidth(0), context.gtexture().getHeight(0));
            case "blendFunc" -> {
                int[] values = IrisMetalUniformValues.irisBlendFunc(context.blendFunction());
                yield new Vector4i(values[0], values[1], values[2], values[3]);
            }
            case "renderStage" -> this.renderStageSource.getAsInt();
            default -> suppliedValue(binding);
        };
    }

    private Object suppliedValue(final Binding binding) {
        if (binding.supplier == null) {
            throw new IllegalStateException("Iris dynamic uniform has no supplier");
        }
        binding.supplierCalls++;
        if (binding.supplier instanceof FloatSupplier value) {
            return value.getAsFloat();
        }
        if (binding.supplier instanceof IntSupplier value) {
            return value.getAsInt();
        }
        if (binding.supplier instanceof BooleanSupplier value) {
            return value.getAsBoolean();
        }
        if (binding.supplier instanceof DoubleSupplier value) {
            return value.getAsDouble();
        }
        if (binding.supplier instanceof Supplier<?> value) {
            return value.get();
        }
        throw new IllegalStateException("Iris dynamic uniform supplier has unsupported type " + binding.supplier);
    }

    /**
     * Supplier results are committed at program-use time. Copy mutable value
     * objects so a later producer mutation cannot alter the bytes observed by
     * Metal trace or staging upload after that commit.
     */
    private static Object snapshotValue(final Object value) {
        if (value instanceof Vector2f vector) {
            return new Vector2f(vector);
        }
        if (value instanceof Vector2i vector) {
            return new Vector2i(vector);
        }
        if (value instanceof Vector3f vector) {
            return new Vector3f(vector);
        }
        if (value instanceof Vector3d vector) {
            return new Vector3d(vector);
        }
        if (value instanceof org.joml.Vector3i vector) {
            return new org.joml.Vector3i(vector);
        }
        if (value instanceof Vector4f vector) {
            return new Vector4f(vector);
        }
        if (value instanceof Vector4i vector) {
            return new Vector4i(vector);
        }
        if (value instanceof Matrix3fc matrix) {
            return new Matrix3f(matrix);
        }
        if (value instanceof Matrix4fc matrix) {
            return new Matrix4f(matrix);
        }
        if (value instanceof float[] values) {
            return values.clone();
        }
        return value;
    }

    private static boolean compatible(final String glslType, final UniformType type) {
        return switch (type) {
            case INT -> "int".equals(glslType) || "bool".equals(glslType);
            case FLOAT -> "float".equals(glslType);
            case MAT3 -> "mat3".equals(glslType);
            case MAT4 -> "mat4".equals(glslType);
            case VEC2 -> "vec2".equals(glslType);
            case VEC2I -> "ivec2".equals(glslType);
            case VEC3 -> "vec3".equals(glslType);
            case VEC3I -> "ivec3".equals(glslType);
            case VEC4 -> "vec4".equals(glslType);
            case VEC4I -> "ivec4".equals(glslType);
        };
    }

    private static boolean writeRegisteredSupplier(
            final MetalIrisShaderCompiler.UniformMember member,
            final ByteBuffer destination,
            final UniformType type,
            final Object value
    ) {
        requireTypeCompatible(member, type);
        int offset = member.offset();
        switch (type) {
            case INT -> destination.putInt(offset, intValue(value));
            case FLOAT -> destination.putFloat(offset, floatValue(value));
            case VEC2 -> {
                if (value instanceof Vector2f vector) {
                    destination.putFloat(offset, vector.x);
                    destination.putFloat(offset + 4, vector.y);
                } else {
                    throw suppliedType(member, value, Vector2f.class);
                }
            }
            case VEC2I -> {
                if (value instanceof Vector2i vector) {
                    destination.putInt(offset, vector.x);
                    destination.putInt(offset + 4, vector.y);
                } else {
                    throw suppliedType(member, value, Vector2i.class);
                }
            }
            case VEC3 -> {
                if (value instanceof Vector3f vector) {
                    destination.putFloat(offset, vector.x);
                    destination.putFloat(offset + 4, vector.y);
                    destination.putFloat(offset + 8, vector.z);
                } else if (value instanceof Vector3d vector) {
                    destination.putFloat(offset, (float) vector.x);
                    destination.putFloat(offset + 4, (float) vector.y);
                    destination.putFloat(offset + 8, (float) vector.z);
                } else if (value instanceof Vector4f vector) {
                    destination.putFloat(offset, vector.x);
                    destination.putFloat(offset + 4, vector.y);
                    destination.putFloat(offset + 8, vector.z);
                } else {
                    throw suppliedType(member, value, Vector3f.class);
                }
            }
            case VEC3I -> {
                if (value instanceof org.joml.Vector3i vector) {
                    destination.putInt(offset, vector.x);
                    destination.putInt(offset + 4, vector.y);
                    destination.putInt(offset + 8, vector.z);
                } else {
                    throw suppliedType(member, value, org.joml.Vector3i.class);
                }
            }
            case VEC4 -> {
                if (value instanceof Vector4f vector) {
                    destination.putFloat(offset, vector.x);
                    destination.putFloat(offset + 4, vector.y);
                    destination.putFloat(offset + 8, vector.z);
                    destination.putFloat(offset + 12, vector.w);
                } else if (value instanceof float[] array && array.length >= 4) {
                    for (int index = 0; index < 4; index++) {
                        destination.putFloat(offset + index * Float.BYTES, array[index]);
                    }
                } else {
                    throw suppliedType(member, value, Vector4f.class);
                }
            }
            case VEC4I -> {
                if (value instanceof Vector4i vector) {
                    destination.putInt(offset, vector.x);
                    destination.putInt(offset + 4, vector.y);
                    destination.putInt(offset + 8, vector.z);
                    destination.putInt(offset + 12, vector.w);
                } else {
                    throw suppliedType(member, value, Vector4i.class);
                }
            }
            case MAT3 -> {
                if (value instanceof Matrix3fc matrix) {
                    putMat3(destination, offset, matrix);
                } else {
                    throw suppliedType(member, value, Matrix3fc.class);
                }
            }
            case MAT4 -> {
                if (value instanceof Matrix4fc matrix) {
                    putMat4(destination, offset, matrix);
                } else {
                    throw suppliedType(member, value, Matrix4fc.class);
                }
            }
        }
        return true;
    }

    private static void requireTypeCompatible(
            final MetalIrisShaderCompiler.UniformMember member,
            final UniformType type
    ) {
        if (!compatible(member.type(), type)) {
            throw new IllegalStateException(
                    "Iris dynamic uniform '" + member.name() + "' registered as " + type
                            + " but shader declares " + member.type()
            );
        }
    }

    private static int intValue(final Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue ? 1 : 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalStateException("Iris dynamic integer value has unsupported type " + value);
    }

    private static float floatValue(final Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        throw new IllegalStateException("Iris dynamic float value has unsupported type " + value);
    }

    private static <T> T suppliedObject(
            final Object value,
            final MetalIrisShaderCompiler.UniformMember member,
            final Class<T> expected
    ) {
        if (!expected.isInstance(value)) {
            throw suppliedType(member, value, expected);
        }
        return expected.cast(value);
    }

    private static IllegalStateException suppliedType(
            final MetalIrisShaderCompiler.UniformMember member,
            final Object actual,
            final Class<?> expected
    ) {
        return new IllegalStateException(
                "Iris dynamic uniform '" + member.name() + "' supplier returned "
                        + (actual == null ? "null" : actual.getClass().getName())
                        + ", expected " + expected.getName()
        );
    }

    private static void putMat3(final ByteBuffer destination, final int offset, final Matrix3fc matrix) {
        destination.putFloat(offset, matrix.m00());
        destination.putFloat(offset + 4, matrix.m01());
        destination.putFloat(offset + 8, matrix.m02());
        destination.putFloat(offset + 16, matrix.m10());
        destination.putFloat(offset + 20, matrix.m11());
        destination.putFloat(offset + 24, matrix.m12());
        destination.putFloat(offset + 32, matrix.m20());
        destination.putFloat(offset + 36, matrix.m21());
        destination.putFloat(offset + 40, matrix.m22());
    }

    private static void putMat4(final ByteBuffer destination, final int offset, final Matrix4fc matrix) {
        for (int column = 0; column < 4; column++) {
            destination.putFloat(offset + column * 16, matrix.get(column, 0));
            destination.putFloat(offset + column * 16 + 4, matrix.get(column, 1));
            destination.putFloat(offset + column * 16 + 8, matrix.get(column, 2));
            destination.putFloat(offset + column * 16 + 12, matrix.get(column, 3));
        }
    }

    private void register(
            final String name,
            final UniformType type,
            final Object supplier,
            final boolean external,
            final UniformUpdateFrequency frequency,
            final ValueUpdateNotifier notifier
    ) {
        Binding prior = this.bindings.putIfAbsent(
                name, new Binding(type, supplier, external, frequency, notifier)
        );
        // Iris intentionally registers a few externally-managed names with
        // multiple GLSL types because different core shader families consume
        // the same logical name differently (for example iris_ModelOffset).
        // Preserve that native admission contract; only conflicting dynamic
        // suppliers are an error.
        if (prior != null && !prior.external && !external
                && (prior.type != type || prior.external != external)) {
            throw new IllegalStateException("Iris dynamic uniform registered with conflicting types: " + name);
        }
    }

    private void register(
            final String name,
            final UniformType type,
            final Object supplier,
            final boolean external
    ) {
        register(name, type, supplier, external, UniformUpdateFrequency.CUSTOM, null);
    }

    private static void require(
            final MetalIrisShaderCompiler.UniformMember member,
            final String expected
    ) {
        if (member.arrayCount() != 0 || !expected.equals(member.type())) {
            throw new IllegalStateException(
                    "Iris dynamic uniform '" + member.name() + "' must be " + expected
                            + ", got " + member.type()
            );
        }
    }

    private static void requireType(final Binding binding, final UniformType expected) {
        if (binding.type != expected) {
            throw new IllegalStateException(
                    "Iris dynamic uniform registration type mismatch: expected " + expected
                            + ", got " + binding.type
            );
        }
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final UniformUpdateFrequency frequency,
            final String name,
            final FloatSupplier supplier
    ) {
        register(name, UniformType.FLOAT, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final UniformUpdateFrequency frequency,
            final String name,
            final IntSupplier supplier
    ) {
        register(name, UniformType.FLOAT, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final UniformUpdateFrequency frequency,
            final String name,
            final DoubleSupplier supplier
    ) {
        register(name, UniformType.FLOAT, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1i(
            final UniformUpdateFrequency frequency,
            final String name,
            final IntSupplier supplier
    ) {
        register(name, UniformType.INT, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1b(
            final UniformUpdateFrequency frequency,
            final String name,
            final BooleanSupplier supplier
    ) {
        register(name, UniformType.INT, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform2f(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector2f> supplier
    ) {
        register(name, UniformType.VEC2, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform2i(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector2i> supplier
    ) {
        register(name, UniformType.VEC2I, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform3f(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector3f> supplier
    ) {
        register(name, UniformType.VEC3, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform3i(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<org.joml.Vector3i> supplier
    ) {
        register(name, UniformType.VEC3I, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform3d(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector3d> supplier
    ) {
        register(name, UniformType.VEC3, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniformTruncated3f(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector4f> supplier
    ) {
        register(name, UniformType.VEC3, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform4f(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector4f> supplier
    ) {
        register(name, UniformType.VEC4, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform4fArray(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<float[]> supplier
    ) {
        register(name, UniformType.VEC4, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniformMatrix(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Matrix4fc> supplier
    ) {
        register(name, UniformType.MAT4, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniformMatrixFromArray(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<float[]> supplier
    ) {
        register(name, UniformType.MAT4, supplier, false, frequency, null);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final String name,
            final FloatSupplier supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.FLOAT, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final String name,
            final IntSupplier supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.FLOAT, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final String name,
            final DoubleSupplier supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.FLOAT, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1i(
            final String name,
            final IntSupplier supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.INT, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform2f(
            final String name,
            final Supplier<Vector2f> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC2, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform2i(
            final String name,
            final Supplier<Vector2i> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC2I, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform3f(
            final String name,
            final Supplier<Vector3f> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC3, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform4f(
            final String name,
            final Supplier<Vector4f> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC4, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform4fArray(
            final String name,
            final Supplier<float[]> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC4, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform4i(
            final String name,
            final Supplier<Vector4i> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC4I, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniformMatrix(
            final String name,
            final Supplier<Matrix4fc> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.MAT4, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniformMatrix3(
            final String name,
            final Supplier<Matrix3fc> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.MAT3, supplier, false, UniformUpdateFrequency.CUSTOM, notifier);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms externallyManagedUniform(
            final String name,
            final UniformType type
    ) {
        register(name, type, null, true, null, null);
        return this;
    }

    void close() {
        for (Binding binding : this.activeBindings) {
            if (binding.notifier != null) {
                binding.notifier.setListener(null);
            }
        }
        this.activeBindings.clear();
        this.activeProgram = null;
        this.activeLayout = null;
        this.committedSnapshot = null;
    }
}
