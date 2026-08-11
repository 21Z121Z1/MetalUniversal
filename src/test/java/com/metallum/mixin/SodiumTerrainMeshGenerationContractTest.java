package com.metallum.mixin;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the Sodium 0.9.1 bytecode boundaries used by terrain ABI mixins. */
final class SodiumTerrainMeshGenerationContractTest {
    private static final String RENDER_SECTION =
            "net/caffeinemc/mods/sodium/client/render/chunk/RenderSection";
    private static final String RENDER_REGION =
            "net/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion";
    private static final String BUILD_OUTPUT =
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput";
    private static final String BUILDER_OUTPUT =
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/BuilderTaskOutput";
    private static final String MESHING_TASK =
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask";
    private static final String SECTION_MANAGER =
            "net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager";
    private static final String CHUNK_RENDERER =
            "net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer";
    private static final String TERRAIN_ICB_SCOPE_MIXIN =
            "com/metallum/mixin/sodium/DefaultChunkRendererTerrainIcbScopeMixin";
    private static final String TERRAIN_ICB_SCOPE =
            "com/metallum/client/metal/render/MetalTerrainIcbScope";
    private static final String WRAP_OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String WRAP_METHOD_ANNOTATION =
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";
    private static final String VANILLA_PIPELINE =
            "net/irisshaders/iris/pipeline/VanillaRenderingPipeline";
    private static final String WORLD_RENDERING_SETTINGS =
            "net/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings";
    private static final String TERRAIN_PASS =
            "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;";

    @Test
    void workerAndOutputShapesStillMatchGenerationCarriers() throws IOException {
        ClassNode meshingTask = readClass(MESHING_TASK);
        assertNotNull(findMethod(
                meshingTask,
                "execute",
                "(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
                        + "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;"
        ), "Sodium changed the exact meshing worker method stamped by the mixin");

        ClassNode output = readClass(BUILD_OUTPUT);
        assertNotNull(findMethod(
                output,
                "<init>",
                "(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/data/TranslucentData;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;"
                        + "Ljava/util/Map;Z)V"
        ), "Sodium changed ChunkBuildOutput construction; audit direct empty-result stamping");
    }

    @Test
    void managerStillOwnsPublishRebuildAndResultDestruction() throws IOException {
        ClassNode manager = readClass(SECTION_MANAGER);
        MethodNode apply = requireMethod(
                manager,
                "applyBuildOutputs",
                "(Ljava/util/ArrayList;)Ljava/util/List;"
        );
        assertEquals(1, countCalls(
                apply,
                RENDER_SECTION,
                "addBuildOutput",
                "(L" + BUILDER_OUTPUT + ";)Z"
        ), "Sodium changed the render-thread publication call redirected by the generation gate");

        assertNotNull(findMethod(
                manager,
                "updateWithResult",
                "(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;"
                        + "Ljava/util/List;)I"
        ), "Sodium changed the accepted-result publication boundary");

        MethodNode submit = requireMethod(
                manager,
                "submitSectionTask",
                "(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;Z)V"
        );
        assertEquals(1, countCalls(
                submit,
                BUILD_OUTPUT,
                "<init>",
                "(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/data/TranslucentData;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;"
                        + "Ljava/util/Map;Z)V"
        ), "Sodium's direct empty-section result no longer uses the stamped output constructor");

        MethodNode process = requireMethod(
                manager,
                "processChunkBuilds",
                "(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"
        );
        assertEquals(1, countCalls(
                process,
                BUILDER_OUTPUT,
                "destroy",
                "()V"
        ), "Sodium no longer has one outer owner for collected build-result destruction");
    }

    @Test
    void regionAndRendererStillExposeTheFailClosedDrawBoundary() throws IOException {
        ClassNode region = readClass(RENDER_REGION);
        FieldNode sections = findField(region, "sections", "[L" + RENDER_SECTION + ";");
        FieldNode flags = findField(region, "sectionFlags", "[B");
        assertNotNull(sections, "Sodium changed RenderRegion.sections; audit region generation cache");
        assertNotNull(flags, "Sodium changed RenderRegion.sectionFlags; audit geometry filtering");
        assertTrue((sections.access & Opcodes.ACC_FINAL) != 0);
        assertTrue((flags.access & Opcodes.ACC_FINAL) != 0);
        assertNotNull(findMethod(region, "delete", "()V"),
                "Sodium changed RenderRegion.delete; audit cache invalidation after region teardown");

        ClassNode renderer = readClass(CHUNK_RENDERER);
        MethodNode render = requireMethod(
                renderer,
                "render",
                "(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;"
                        + TERRAIN_PASS
                        + "Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;"
                        + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;Z"
                        + "Lcom/mojang/blaze3d/textures/GpuSampler;"
                        + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                        + "Lcom/mojang/blaze3d/buffers/GpuBuffer;)V"
        );
        assertEquals(2, countCalls(
                render,
                RENDER_REGION,
                "getStorage",
                "(" + TERRAIN_PASS + ")"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;"
        ), "Sodium changed one of the two region storage reads guarded by the draw gate");
    }

    @Test
    void vanillaPipelineStillRestoresCompactAbiBeforeConstructorReturn() throws IOException {
        MethodNode constructor = requireMethod(readClass(VANILLA_PIPELINE), "<init>", "()V");
        boolean restoresCompact = false;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !WORLD_RENDERING_SETTINGS.equals(call.owner)
                    || !"setVertexFormat".equals(call.name)
                    || !"(Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V"
                            .equals(call.desc)) {
                continue;
            }
            AbstractInsnNode argument = previousMeaningful(instruction);
            if (argument instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && "net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats"
                            .equals(field.owner)
                    && "COMPACT".equals(field.name)) {
                restoresCompact = true;
                break;
            }
        }
        assertTrue(restoresCompact,
                "Iris VanillaRenderingPipeline no longer restores ChunkMeshFormats.COMPACT;"
                        + " audit the shaders-off ready hook");
    }

    @Test
    void terrainIcbScopeWrapperHasAnExceptionFinallyForTheExactRenderer() throws IOException {
        MethodNode render = requireMethod(
                readClass(CHUNK_RENDERER),
                "render",
                "(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;"
                        + TERRAIN_PASS
                        + "Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;"
                        + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;Z"
                        + "Lcom/mojang/blaze3d/textures/GpuSampler;"
                        + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                        + "Lcom/mojang/blaze3d/buffers/GpuBuffer;)V"
        );
        assertNotNull(render, "Sodium changed the renderer method wrapped for terrain ICB scope");

        MethodNode wrapper = requireMethod(
                readClass(TERRAIN_ICB_SCOPE_MIXIN),
                "metallum$renderInTerrainIcbScope",
                "(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;"
                        + TERRAIN_PASS
                        + "Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;"
                        + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;Z"
                        + "Lcom/mojang/blaze3d/textures/GpuSampler;"
                        + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                        + "Lcom/mojang/blaze3d/buffers/GpuBuffer;"
                        + "L" + WRAP_OPERATION + ";)V"
        );
        assertTrue(wrapper.visibleAnnotations != null
                        && wrapper.visibleAnnotations.stream()
                        .anyMatch(annotation -> WRAP_METHOD_ANNOTATION.equals(annotation.desc)),
                "terrain ICB scope mixin is not registered as a method wrapper");
        assertEquals(1, countCalls(wrapper, TERRAIN_ICB_SCOPE, "enter", "()V"));
        assertEquals(2, countCalls(wrapper, TERRAIN_ICB_SCOPE, "exit", "()V"),
                "the normal and exceptional finally paths must both unwind the scope");
        assertEquals(1, countCalls(wrapper, WRAP_OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;"));
        assertTrue(!wrapper.tryCatchBlocks.isEmpty(),
                "terrain ICB scope wrapper has no exception handler; a renderer failure would leak scope");
    }

    private static ClassNode readClass(final String name) throws IOException {
        try (InputStream input = SodiumTerrainMeshGenerationContractTest.class
                .getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name + " bytecode is missing from the test runtime classpath");
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode requireMethod(
            final ClassNode owner,
            final String name,
            final String descriptor
    ) {
        MethodNode method = findMethod(owner, name, descriptor);
        assertNotNull(method, owner.name + "." + name + descriptor + " is missing");
        return method;
    }

    private static MethodNode findMethod(
            final ClassNode owner,
            final String name,
            final String descriptor
    ) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst()
                .orElse(null);
    }

    private static FieldNode findField(
            final ClassNode owner,
            final String name,
            final String descriptor
    ) {
        return owner.fields.stream()
                .filter(field -> name.equals(field.name) && descriptor.equals(field.desc))
                .findFirst()
                .orElse(null);
    }

    private static int countCalls(
            final MethodNode method,
            final String owner,
            final String name,
            final String descriptor
    ) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode previousMeaningful(final AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }
}
