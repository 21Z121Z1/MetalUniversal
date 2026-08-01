package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CommonUniforms;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
    private record Binding(UniformType type, Object supplier, boolean external) {
    }

    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final IntSupplier renderStageSource;

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
                && !binding.external()
                && member.arrayCount() == 0
                && compatible(member.type(), binding.type());
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
        Binding binding = this.bindings.get(member.name());
        if (binding == null) {
            return false;
        }
        if (binding.external()) {
            return false;
        }
        int offset = member.offset();
        switch (member.name()) {
            case "entityId" -> {
                require(member, "int");
                requireType(binding, UniformType.INT);
                destination.putInt(offset, ((IntSupplier) binding.supplier()).getAsInt());
                return true;
            }
            case "atlasSize" -> {
                require(member, "ivec2");
                requireType(binding, UniformType.VEC2I);
                destination.putInt(offset, context.atlasWidth());
                destination.putInt(offset + 4, context.atlasHeight());
                return true;
            }
            case "gtextureId" -> {
                require(member, "int");
                requireType(binding, UniformType.INT);
                destination.putInt(offset, context.gtexture() == null
                        ? 0
                        : IrisMetalUniformValues.logicalTextureIdForDynamic(context.gtexture()));
                return true;
            }
            case "textureReloadCount" -> {
                require(member, "int");
                requireType(binding, UniformType.INT);
                destination.putInt(offset, ((IntSupplier) binding.supplier()).getAsInt());
                return true;
            }
            case "gtextureSize" -> {
                require(member, "ivec2");
                requireType(binding, UniformType.VEC2I);
                if (context.gtexture() == null) {
                    destination.putInt(offset, 0);
                    destination.putInt(offset + 4, 0);
                } else {
                    destination.putInt(offset, context.gtexture().getWidth(0));
                    destination.putInt(offset + 4, context.gtexture().getHeight(0));
                }
                return true;
            }
            case "blendFunc" -> {
                require(member, "ivec4");
                requireType(binding, UniformType.VEC4I);
                int[] blend = IrisMetalUniformValues.irisBlendFunc(context.blendFunction());
                for (int index = 0; index < blend.length; index++) {
                    destination.putInt(offset + index * Integer.BYTES, blend[index]);
                }
                return true;
            }
            case "renderStage" -> {
                require(member, "int");
                requireType(binding, UniformType.INT);
                destination.putInt(offset, this.renderStageSource.getAsInt());
                return true;
            }
            default -> {
                return writeRegisteredSupplier(member, destination, binding);
            }
        }
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
            final Binding binding
    ) {
        requireTypeCompatible(member, binding.type());
        int offset = member.offset();
        switch (binding.type()) {
            case INT -> destination.putInt(offset, intValue(binding.supplier()));
            case FLOAT -> destination.putFloat(offset, floatValue(binding.supplier()));
            case VEC2 -> {
                Object value = suppliedObject(binding);
                if (value instanceof Vector2f vector) {
                    destination.putFloat(offset, vector.x);
                    destination.putFloat(offset + 4, vector.y);
                } else {
                    throw suppliedType(member, value, Vector2f.class);
                }
            }
            case VEC2I -> {
                Object value = suppliedObject(binding);
                if (value instanceof Vector2i vector) {
                    destination.putInt(offset, vector.x);
                    destination.putInt(offset + 4, vector.y);
                } else {
                    throw suppliedType(member, value, Vector2i.class);
                }
            }
            case VEC3 -> {
                Object value = suppliedObject(binding);
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
                Object value = suppliedObject(binding);
                if (value instanceof org.joml.Vector3i vector) {
                    destination.putInt(offset, vector.x);
                    destination.putInt(offset + 4, vector.y);
                    destination.putInt(offset + 8, vector.z);
                } else {
                    throw suppliedType(member, value, org.joml.Vector3i.class);
                }
            }
            case VEC4 -> {
                Object value = suppliedObject(binding);
                if (value instanceof Vector4f vector) {
                    destination.putFloat(offset, vector.x);
                    destination.putFloat(offset + 4, vector.y);
                    destination.putFloat(offset + 8, vector.z);
                    destination.putFloat(offset + 12, vector.w);
                } else {
                    throw suppliedType(member, value, Vector4f.class);
                }
            }
            case VEC4I -> {
                Object value = suppliedObject(binding);
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
                Object value = suppliedObject(binding);
                if (value instanceof Matrix3fc matrix) {
                    putMat3(destination, offset, matrix);
                } else {
                    throw suppliedType(member, value, Matrix3fc.class);
                }
            }
            case MAT4 -> {
                Object value = suppliedObject(binding);
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

    private static Object suppliedObject(final Binding binding) {
        if (!(binding.supplier() instanceof Supplier<?> supplier)) {
            throw new IllegalStateException(
                    "Iris dynamic uniform supplier is not an object supplier for " + binding.type()
            );
        }
        return supplier.get();
    }

    private static int intValue(final Object supplier) {
        if (supplier instanceof IntSupplier value) {
            return value.getAsInt();
        }
        if (supplier instanceof BooleanSupplier value) {
            return value.getAsBoolean() ? 1 : 0;
        }
        throw new IllegalStateException("Iris dynamic integer supplier has unsupported type " + supplier);
    }

    private static float floatValue(final Object supplier) {
        if (supplier instanceof FloatSupplier value) {
            return value.getAsFloat();
        }
        if (supplier instanceof IntSupplier value) {
            return value.getAsInt();
        }
        if (supplier instanceof DoubleSupplier value) {
            return (float) value.getAsDouble();
        }
        throw new IllegalStateException("Iris dynamic float supplier has unsupported type " + supplier);
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
            final boolean external
    ) {
        Binding prior = this.bindings.putIfAbsent(name, new Binding(type, supplier, external));
        // Iris intentionally registers a few externally-managed names with
        // multiple GLSL types because different core shader families consume
        // the same logical name differently (for example iris_ModelOffset).
        // Preserve that native admission contract; only conflicting dynamic
        // suppliers are an error.
        if (prior != null && !prior.external() && !external
                && (prior.type() != type || prior.external() != external)) {
            throw new IllegalStateException("Iris dynamic uniform registered with conflicting types: " + name);
        }
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
        if (binding.type() != expected) {
            throw new IllegalStateException(
                    "Iris dynamic uniform registration type mismatch: expected " + expected
                            + ", got " + binding.type()
            );
        }
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final UniformUpdateFrequency frequency,
            final String name,
            final FloatSupplier supplier
    ) {
        register(name, UniformType.FLOAT, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final UniformUpdateFrequency frequency,
            final String name,
            final IntSupplier supplier
    ) {
        register(name, UniformType.FLOAT, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final UniformUpdateFrequency frequency,
            final String name,
            final DoubleSupplier supplier
    ) {
        register(name, UniformType.FLOAT, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1i(
            final UniformUpdateFrequency frequency,
            final String name,
            final IntSupplier supplier
    ) {
        register(name, UniformType.INT, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1b(
            final UniformUpdateFrequency frequency,
            final String name,
            final BooleanSupplier supplier
    ) {
        register(name, UniformType.INT, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform2f(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector2f> supplier
    ) {
        register(name, UniformType.VEC2, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform2i(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector2i> supplier
    ) {
        register(name, UniformType.VEC2I, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform3f(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector3f> supplier
    ) {
        register(name, UniformType.VEC3, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform3i(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<org.joml.Vector3i> supplier
    ) {
        register(name, UniformType.VEC3I, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform3d(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector3d> supplier
    ) {
        register(name, UniformType.VEC3, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniformTruncated3f(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector4f> supplier
    ) {
        register(name, UniformType.VEC3, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform4f(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Vector4f> supplier
    ) {
        register(name, UniformType.VEC4, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform4fArray(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<float[]> supplier
    ) {
        register(name, UniformType.VEC4, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniformMatrix(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<Matrix4fc> supplier
    ) {
        register(name, UniformType.MAT4, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniformMatrixFromArray(
            final UniformUpdateFrequency frequency,
            final String name,
            final Supplier<float[]> supplier
    ) {
        register(name, UniformType.MAT4, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final String name,
            final FloatSupplier supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.FLOAT, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final String name,
            final IntSupplier supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.FLOAT, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1f(
            final String name,
            final DoubleSupplier supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.FLOAT, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform1i(
            final String name,
            final IntSupplier supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.INT, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform2f(
            final String name,
            final Supplier<Vector2f> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC2, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform2i(
            final String name,
            final Supplier<Vector2i> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC2I, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform3f(
            final String name,
            final Supplier<Vector3f> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC3, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform4f(
            final String name,
            final Supplier<Vector4f> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC4, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform4fArray(
            final String name,
            final Supplier<float[]> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC4, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniform4i(
            final String name,
            final Supplier<Vector4i> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.VEC4I, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniformMatrix(
            final String name,
            final Supplier<Matrix4fc> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.MAT4, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms uniformMatrix3(
            final String name,
            final Supplier<Matrix3fc> supplier,
            final ValueUpdateNotifier notifier
    ) {
        register(name, UniformType.MAT3, supplier, false);
        return this;
    }

    @Override
    public IrisMetalDynamicUniforms externallyManagedUniform(
            final String name,
            final UniformType type
    ) {
        register(name, type, null, true);
        return this;
    }
}
