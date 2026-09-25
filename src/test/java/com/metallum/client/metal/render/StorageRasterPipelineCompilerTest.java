package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import static org.junit.jupiter.api.Assertions.*;

/** Reflection behavior only: no native device, optional mods or string-source assertions. */
class StorageRasterPipelineCompilerTest {
    private record Type(int baseType, int dimensions, int vectorSize, int arrayDimensions)
            implements SpvModule.Reflection.Type {
        @Override public int arrayLength(int index) { return 2; }
    }
    private static final class Descriptor implements SpvModule.Reflection.Descriptor {
        private final String name;
        private final int resourceType;
        private final Type type;
        private int binding;
        private int set;
        Descriptor(String name, int kind, int binding, int dimension) {
            this.name = name; this.resourceType = kind; this.binding = binding;
            this.type = new Type(Spvc.SPVC_BASETYPE_STRUCT, dimension, 1, 0);
        }
        @Override public String name() { return name; }
        @Override public int resourceType() { return resourceType; }
        @Override public Type type() { return type; }
        @Override public int binding() { return binding; }
        @Override public void binding(int value) { binding = value; }
        @Override public int descriptorSetIndex() { return set; }
        @Override public void descriptorSetIndex(int value) { set = value; }
    }
    private static SpvModule.Reflection reflection(Descriptor... descriptors) {
        return new SpvModule.Reflection() {
            @Override public List<InterfaceVariable> inputs() { return List.of(); }
            @Override public List<InterfaceVariable> outputs() { return List.of(); }
            @Override public List<Descriptor> descriptors() { return List.of(descriptors); }
            @Override public List<Descriptor> descriptors(int type) {
                return descriptors().stream().filter(value -> value.resourceType() == type).toList();
            }
            @Override public List<PushConstant> pushConstants() { return List.of(); }
        };
    }
    private static Descriptor buffer(int binding) {
        return new Descriptor("ContractState", Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, binding, Integer.MAX_VALUE);
    }
    private static List<BindGroupLayout.UniformDescription> uniforms(String name, UniformType type) {
        return BindGroupLayout.flattenUniforms(List.of(BindGroupLayout.builder().withUniform(name, type).build()));
    }

    @Test void storageLogicalIndexSurvivesDenseUniformRemapping() throws Exception {
        var ssbo = buffer(13);
        var image = new Descriptor("contractImage", Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, 3, Spv.SpvDim2D);
        var sampler = new Descriptor("colortex0", Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, 12, Spv.SpvDim2D);
        var plan = StorageRasterPipelineCompiler.bindings(uniforms("colortex0", UniformType.COMBINED_IMAGE_SAMPLER),
                List.of(reflection(ssbo, sampler, image)));
        assertEquals(13, ssbo.binding());
        assertEquals(3, image.binding());
        assertEquals(0, sampler.binding());
        assertEquals(0, plan.indices().getInt("colortex0"));
        assertEquals(-1, plan.indices().getInt("ContractState"));
        assertEquals(1, plan.uniforms().size());
    }

    @Test void ordinaryUniformHasOneDenseBindingAcrossStages() throws Exception {
        var a = new Descriptor("Camera", Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, 5, Integer.MAX_VALUE);
        var b = new Descriptor("Camera", Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, 9, Integer.MAX_VALUE);
        var plan = StorageRasterPipelineCompiler.bindings(uniforms("Camera", UniformType.UNIFORM_BUFFER),
                List.of(reflection(a, buffer(1)), reflection(b, buffer(1))));
        assertEquals(1, plan.uniforms().size());
        assertEquals(a.binding(), b.binding());
    }

    @Test void storageCannotBeRelabeledAsReadOnlyUniform() {
        assertThrows(ShaderCompileException.class, () -> StorageRasterPipelineCompiler.bindings(
                uniforms("ContractState", UniformType.UNIFORM_BUFFER), List.of(reflection(buffer(1)))));
    }

    @Test void mismatchedLogicalStorageBindingFailsClosed() {
        assertThrows(ShaderCompileException.class, () -> StorageRasterPipelineCompiler.bindings(
                List.of(), List.of(reflection(buffer(1)), reflection(buffer(2)))));
    }

    @Test void storageImageDimensionMustMatchExistingTwoDimensionalBackend() {
        var image = new Descriptor("image", Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, 3, Spv.SpvDim3D);
        assertThrows(ShaderCompileException.class, () -> StorageRasterPipelineCompiler.bindings(
                List.of(), List.of(reflection(image))));
    }

    @Test void storageDescriptorSetIsNotSilentlyRemapped() {
        var ssbo = buffer(1); ssbo.descriptorSetIndex(2);
        assertThrows(ShaderCompileException.class, () -> StorageRasterPipelineCompiler.bindings(
                List.of(), List.of(reflection(ssbo))));
    }

    @Test void unknownOrdinaryUniformRemainsAnError() {
        var unknown = new Descriptor("Missing", Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, 2, Integer.MAX_VALUE);
        assertThrows(ShaderCompileException.class, () -> StorageRasterPipelineCompiler.bindings(
                List.of(), List.of(reflection(buffer(1), unknown))));
    }

    @Test void ordinaryPipelineCannotAccidentallyEnterTheStorageExtension() {
        var camera = new Descriptor("Camera", Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, 0, Integer.MAX_VALUE);
        assertThrows(ShaderCompileException.class, () -> StorageRasterPipelineCompiler.bindings(
                uniforms("Camera", UniformType.UNIFORM_BUFFER), List.of(reflection(camera))));
    }

    @Test void descriptorKindCannotChangeAcrossStages() {
        var image = new Descriptor("ContractState", Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, 1, Spv.SpvDim2D);
        assertThrows(ShaderCompileException.class, () -> StorageRasterPipelineCompiler.bindings(
                List.of(), List.of(reflection(buffer(1)), reflection(image))));
    }

    @Test void duplicateResourceNamesAreRejectedInsteadOfLastWriterWins() {
        assertThrows(ShaderCompileException.class, () -> StorageRasterPipelineCompiler.bindings(
                List.of(), List.of(reflection(buffer(1), buffer(1)))));
    }

    private static SpvModule.Reflection.InterfaceVariable varying(int location, int baseType, int vectorSize, boolean flat) {
        return new SpvModule.Reflection.InterfaceVariable() {
            @Override public String name() { return "value"; }
            @Override public SpvModule.Reflection.Type type() { return new Type(baseType, Integer.MAX_VALUE, vectorSize, 0); }
            @Override public int location() { return location; }
            @Override public void location(int value) { throw new UnsupportedOperationException(); }
            @Override public int decoration(int decoration) { return decoration == Spv.SpvDecorationFlat && flat ? 1 : 0; }
        };
    }

    @Test void linkedVaryingsPreserveTypeShapeAndInterpolation() throws Exception {
        var vertex = varying(1, Spvc.SPVC_BASETYPE_FP32, 2, false);
        StorageRasterPipelineCompiler.checkVaryings(List.of(vertex), List.of(vertex));
        for (var fragment : List.of(varying(1, Spvc.SPVC_BASETYPE_FP32, 3, false),
                varying(1, Spvc.SPVC_BASETYPE_INT32, 2, false),
                varying(1, Spvc.SPVC_BASETYPE_FP32, 2, true),
                varying(2, Spvc.SPVC_BASETYPE_FP32, 2, false))) {
            assertThrows(ShaderCompileException.class, () -> StorageRasterPipelineCompiler.checkVaryings(
                    List.of(vertex), List.of(fragment)));
        }
    }

    @Test void overlappingLocationsAreNotSilentlyOverwritten() {
        var value = varying(1, Spvc.SPVC_BASETYPE_FP32, 2, false);
        assertThrows(ShaderCompileException.class, () -> StorageRasterPipelineCompiler.checkVaryings(
                List.of(value, value), List.of(value)));
    }
}
