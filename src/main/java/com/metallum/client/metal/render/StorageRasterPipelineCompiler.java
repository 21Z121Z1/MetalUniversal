package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.SpvUtil;
import com.mojang.renderpearl.util.ShaderCompileException;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;

/**
 * Narrow 26.3 frontend extension for explicitly admitted raster storage resources.
 * RenderPearl's UniformType has no SSBO/image alternative. Its ordinary PipelineBuilder
 * remains authoritative for all other pipelines. This adapter uses the same GLSL compiler,
 * frontend object, device cache and Metal backend; storage indices remain logical until
 * MetalCrossShaderCompiler lowers them. It never substitutes storage with read-only UBOs.
 */
final class StorageRasterPipelineCompiler implements AutoCloseable {
    private final GpuDeviceBackend device;
    private final GlslCompiler compiler;

    StorageRasterPipelineCompiler(GpuDeviceBackend device) {
        this.device = device;
        var info = device.getDeviceInfo();
        compiler = new GlslCompiler(info.isZZeroToOne(), info.features().shaderDrawParameters());
    }

    FrontendRenderPipeline compile(RenderPipeline pipeline, ShaderSource source) {
        var modules = new EnumMap<ShaderType, SpvModule>(ShaderType.class);
        BackendRenderPipeline backend = null;
        try {
            if (!pipeline.getShaders().keySet().equals(java.util.Set.of(ShaderType.VERTEX, ShaderType.FRAGMENT))) {
                throw new ShaderCompileException("Raster storage requires a vertex/fragment pair");
            }
            var shaders = new ArrayList<BackendRenderPipeline.CreateInfo.Shader>();
            for (ShaderType stage : List.of(ShaderType.VERTEX, ShaderType.FRAGMENT)) {
                var id = pipeline.getShaders().get(stage);
                String glsl = source.getShader(id, stage);
                if (glsl == null) throw new ShaderCompileException("Missing raster storage shader " + id);
                SpvModule module = compiler.compileToSpv(id.toString(), glsl, stage, pipeline.getShaderDefines(), source);
                modules.put(stage, module);
                shaders.add(new BackendRenderPipeline.CreateInfo.Shader(id.toString(), "main", module));
            }
            var vertex = modules.get(ShaderType.VERTEX).reflect();
            var fragment = modules.get(ShaderType.FRAGMENT).reflect();
            var vertexBuffers = new ArrayList<BackendRenderPipeline.CreateInfo.VertexBuffer>();
            var attributes = new ArrayList<BackendRenderPipeline.CreateInfo.AttribBinding>();
            vertexLayout(pipeline, vertex.inputs(), vertexBuffers, attributes);
            checkVaryings(vertex.outputs(), fragment.inputs());
            BindingPlan plan = bindings(BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()),
                    List.of(vertex, fragment));
            for (SpvModule.Reflection reflection : List.of(vertex, fragment)) {
                var constants = reflection.pushConstants();
                if (constants.size() > 1 || (!constants.isEmpty() &&
                        (constants.getFirst().size() < 0 || constants.getFirst().size() > pipeline.pushConstantSize()))) {
                    throw new ShaderCompileException("Raster storage push constants exceed the declared contract");
                }
            }
            var info = new BackendRenderPipeline.CreateInfo(pipeline.getLocation().toString(),
                    List.copyOf(shaders), List.copyOf(vertexBuffers), List.copyOf(attributes), plan.uniforms,
                    pipeline.pushConstantSize(), pipeline.getDepthStencilState(), pipeline.getPolygonMode(),
                    pipeline.isCull(), pipeline.getColorTargetStates(), pipeline.getPrimitiveTopology());
            backend = device.compilePipeline(info).finishCompile();
            if (backend == null) throw new IllegalStateException("Backend rejected raster storage pipeline " + pipeline.getLocation());
            var result = new FrontendRenderPipeline(pipeline.getLocation().toString(), backend,
                    Collections.unmodifiableList(new ArrayList<>(pipeline.getVertexFormatBindings())),
                    Object2IntMaps.unmodifiable(plan.indices),
                    BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()),
                    Collections.unmodifiableList(new ArrayList<>(pipeline.getColorTargetStates())),
                    pipeline.wantsDepthTexture(), pipeline.pushConstantSize());
            backend = null; // The existing frontend/cache now owns the native pipeline.
            return result;
        } catch (ShaderCompileException failure) {
            throw new IllegalStateException("Raster storage contract rejected " + pipeline.getLocation(), failure);
        } finally {
            if (backend != null) backend.close();
            modules.values().forEach(SpvModule::close);
        }
    }

    record BindingPlan(List<BindGroupLayout.UniformDescription> uniforms, Object2IntOpenHashMap<String> indices) { }
    private record DescriptorShape(int resourceType, int dimensions, int arrayDimensions) { }

    /** Package-visible for behavior tests with reflection fixtures, independent of Metal or optional mods. */
    static BindingPlan bindings(List<BindGroupLayout.UniformDescription> declared,
                                List<SpvModule.Reflection> stages) throws ShaderCompileException {
        Map<String, BindGroupLayout.UniformDescription> descriptions = new LinkedHashMap<>();
        for (var uniform : declared) {
            var previous = descriptions.putIfAbsent(uniform.name(), uniform);
            if (previous != null && !previous.equals(uniform)) {
                throw new ShaderCompileException("Conflicting uniform declaration " + uniform.name());
            }
        }
        var indices = new Object2IntOpenHashMap<String>();
        indices.defaultReturnValue(-1);
        var uniforms = new ArrayList<BindGroupLayout.UniformDescription>();
        Map<String, DescriptorShape> shapes = new LinkedHashMap<>();
        Map<String, Integer> logicalStorage = new LinkedHashMap<>();
        boolean hasStorage = false;
        for (var reflection : stages) {
            var seen = new java.util.HashSet<String>();
            for (var descriptor : reflection.descriptors()) {
                String name = descriptor.name();
                if (name == null || name.isBlank() || !seen.add(name)) {
                    throw new ShaderCompileException("Missing or duplicate shader resource name");
                }
                var type = descriptor.type();
                var shape = new DescriptorShape(descriptor.resourceType(), type.dimensions(), type.arrayDimensions());
                DescriptorShape previous = shapes.putIfAbsent(name, shape);
                if (previous != null && !previous.equals(shape)) {
                    throw new ShaderCompileException("Resource type changes between stages: " + name);
                }
                if (type.arrayDimensions() != 0) {
                    throw new ShaderCompileException("Descriptor arrays need an explicit array binding contract: " + name);
                }
                boolean storageBuffer = descriptor.resourceType() == Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER;
                boolean storageImage = descriptor.resourceType() == Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE;
                if (storageBuffer || storageImage) {
                    hasStorage = true;
                    if (descriptions.containsKey(name) || descriptor.binding() < 0 || descriptor.descriptorSetIndex() != 0) {
                        throw new ShaderCompileException("Invalid raster storage binding: " + name);
                    }
                    if (storageImage && type.dimensions() != Spv.SpvDim2D) {
                        throw new ShaderCompileException("Only 2D raster storage images have a binding contract: " + name);
                    }
                    Integer prior = logicalStorage.putIfAbsent(name, descriptor.binding());
                    if (prior != null && prior != descriptor.binding()) {
                        throw new ShaderCompileException("Logical storage binding changes between stages: " + name);
                    }
                    // Crucially do not remap this index. The shared Metal compiler joins Iris logical resources.
                    continue;
                }
                var uniform = descriptions.get(name);
                if (uniform == null || SpvUtil.resourceType(uniform.type()) != descriptor.resourceType()) {
                    throw new ShaderCompileException("No matching ordinary uniform declaration: " + name);
                }
                int dim = type.dimensions();
                boolean validDimensions = dim == Integer.MAX_VALUE ||
                        (uniform.type() == UniformType.TEXEL_BUFFER ? dim == Spv.SpvDimBuffer :
                                uniform.type() == UniformType.COMBINED_IMAGE_SAMPLER &&
                                        (dim == Spv.SpvDim2D || dim == Spv.SpvDimCube || dim == Spv.SpvDimRect));
                if (!validDimensions) throw new ShaderCompileException("Uniform dimensions do not match: " + name);
                int index = indices.getInt(name);
                if (index < 0) {
                    index = uniforms.size();
                    indices.put(name, index);
                    uniforms.add(uniform);
                }
                descriptor.binding(index);
                descriptor.descriptorSetIndex(0);
            }
        }
        if (!hasStorage) throw new ShaderCompileException("Storage extension requested without reflected storage");
        return new BindingPlan(List.copyOf(uniforms), indices);
    }

    private record Varying(int baseType, int vectorSize, boolean flat) { }

    static void checkVaryings(List<SpvModule.Reflection.InterfaceVariable> outputs,
                              List<SpvModule.Reflection.InterfaceVariable> inputs) throws ShaderCompileException {
        var outputSlots = slots(outputs);
        for (var entry : slots(inputs).entrySet()) {
            if (!entry.getValue().equals(outputSlots.get(entry.getKey()))) {
                throw new ShaderCompileException("Raster stage interface mismatch at location " + entry.getKey());
            }
        }
    }

    private static Map<Integer, Varying> slots(List<SpvModule.Reflection.InterfaceVariable> variables) throws ShaderCompileException {
        Map<Integer, Varying> slots = new LinkedHashMap<>();
        for (var variable : variables) {
            var type = variable.type();
            if (variable.location() < 0 || variable.decoration(Spv.SpvDecorationComponent) != 0 ||
                    type.vectorSize() < 1 || type.vectorSize() > 4 ||
                    type.baseType() == Spvc.SPVC_BASETYPE_INT64 || type.baseType() == Spvc.SPVC_BASETYPE_UINT64 ||
                    type.baseType() == Spvc.SPVC_BASETYPE_FP64 || type.baseType() == Spvc.SPVC_BASETYPE_STRUCT) {
                throw new ShaderCompileException("Unsupported raster interface " + variable.name());
            }
            long count = 1;
            for (int dimension = 0; dimension < type.arrayDimensions(); dimension++) {
                int length = type.arrayLength(dimension);
                if (length <= 0 || (count *= length) > 4096) throw new ShaderCompileException("Unbounded raster interface array");
            }
            var varying = new Varying(type.baseType(), type.vectorSize(), variable.hasDecoration(Spv.SpvDecorationFlat));
            for (int offset = 0; offset < count; offset++) {
                long location = (long) variable.location() + offset;
                if (location > Integer.MAX_VALUE || slots.putIfAbsent((int) location, varying) != null) {
                    throw new ShaderCompileException("Overlapping raster interface locations");
                }
            }
        }
        return slots;
    }

    private static void vertexLayout(RenderPipeline pipeline, List<SpvModule.Reflection.InterfaceVariable> inputs,
            List<BackendRenderPipeline.CreateInfo.VertexBuffer> buffers,
            List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes) throws ShaderCompileException {
        Map<String, SpvModule.Reflection.InterfaceVariable> byName = new LinkedHashMap<>();
        for (var input : inputs) {
            if (byName.putIfAbsent(input.name(), input) != null) throw new ShaderCompileException("Duplicate vertex input");
        }
        Map<Integer, GpuFormat> formats = new LinkedHashMap<>();
        String previousName = null;
        int previousLocation = -1;
        var bindings = pipeline.getVertexFormatBindings();
        for (int binding = 0; binding < bindings.size(); binding++) {
            var format = bindings.get(binding);
            if (format == null) continue;
            buffers.add(new BackendRenderPipeline.CreateInfo.VertexBuffer(binding, format.getVertexSize(), format.getStepRate()));
            for (var element : format.getElements()) {
                var input = byName.get(element.name());
                if (input == null) continue;
                int location = element.name().equals(previousName) ? previousLocation + 1 : input.location();
                if (location < 0 || formats.putIfAbsent(location, element.format()) != null) {
                    throw new ShaderCompileException("Overlapping vertex input binding");
                }
                attributes.add(new BackendRenderPipeline.CreateInfo.AttribBinding(binding, location, element.offset(), element.format()));
                previousName = element.name();
                previousLocation = location;
            }
        }
        for (var input : inputs) {
            var format = formats.get(input.location());
            if (format == null || input.decoration(Spv.SpvDecorationComponent) != 0) {
                throw new ShaderCompileException("Missing vertex attribute " + input.name());
            }
            int expected = switch (format.componentType()) {
                case UNORM_8, SNORM_8, UNORM_16, SNORM_16, FLOAT_16, FLOAT_32 -> Spvc.SPVC_BASETYPE_FP32;
                case UINT_8, UINT_16, UINT_32 -> Spvc.SPVC_BASETYPE_UINT32;
                case SINT_8, SINT_16, SINT_32 -> Spvc.SPVC_BASETYPE_INT32;
                default -> throw new ShaderCompileException("Unsupported vertex component type " + format.componentType());
            };
            if (input.type().baseType() != expected || input.type().vectorSize() > format.componentCount()) {
                throw new ShaderCompileException("Vertex attribute type mismatch " + input.name());
            }
        }
    }

    @Override public void close() { compiler.close(); }
}
