package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Inspects the actual Minecraft classes used to compile this candidate. These
 * are producer/consumer call-shape and ordering checks, not rendering claims.
 * No Minecraft or native renderer static initializer is invoked by the probe.
 */
final class MinecraftMetalFxSourceContractTest {
    private static final String GAME = "net/minecraft/client/renderer/GameRenderer";
    private static final String LEVEL = "net/minecraft/client/renderer/LevelRenderer";
    private static final String MIXIN = "com/metallum/mixin/render/GameRendererMetalFxMixin";
    private static final String ENCODER = "com/mojang/renderpearl/api/commands/CommandEncoder";
    private static final String TARGET = "com/mojang/blaze3d/pipeline/RenderTarget";
    private static final String CLEAR = "(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V";

    @Test
    void mainSceneHasExplicitUnormColorAndFloatDepthStorage() {
        MethodNode ctor = method("com/mojang/blaze3d/pipeline/MainTarget", "<init>");
        List<String> formats = new ArrayList<>();
        for (var node : ctor.instructions) {
            if (node instanceof FieldInsnNode f && f.owner.equals("com/mojang/renderpearl/api/GpuFormat")) {
                formats.add(f.name);
            }
        }
        assertEquals(List.of("RGBA8_UNORM", "D32_FLOAT"), formats);
        // This deliberately proves only storage. Transfer semantics are tested
        // by the color pipeline's separate numerical/descriptor contract.
    }

    @Test
    void mainAndHudDepthShareConstructionAndResizeDimensionOwners() {
        MethodNode ctor = method(GAME, "<init>");
        assertEquals(1, calls(ctor, "com/mojang/blaze3d/pipeline/MainTarget", "<init>").size());
        assertEquals(1, calls(ctor, "com/mojang/blaze3d/pipeline/TextureTarget", "<init>").size());
        assertTrue(strings(ctor).contains("hud_3d_depth"));
        assertEquals(2, calls(method(GAME, "resize"), TARGET, "resize").size());
        for (String hook : List.of("metallum$createSceneTarget", "metallum$createHudDepthTarget",
                "metallum$resizeSceneTarget")) {
            MethodNode m = method(MIXIN, hook);
            assertEquals(1, calls(m, "com/metallum/client/metal/render/MetalFxManager", "sceneWidth").size());
            assertEquals(1, calls(m, "com/metallum/client/metal/render/MetalFxManager", "sceneHeight").size());
        }
    }

    @Test
    void handDepthClearPrecedesHandAndOptionalWorldIntegration() {
        MethodNode hud = method(GAME, "render3dHud");
        assertEquals(1, calls(hud, ENCODER, "clearDepthTexture").size());
        MethodInsnNode clear = calls(hud, ENCODER, "clearDepthTexture").getFirst();
        assertEquals(CLEAR, clear.desc);
        assertEquals(Opcodes.DCONST_0, previousOpcode(clear).getOpcode(), "26.3 uses reversed depth with far clear zero");
        int clearIndex = hud.instructions.indexOf(clear);
        assertTrue(clearIndex < index(hud, GAME, "renderItemInHand"));
        assertTrue(index(hud, GAME, "renderItemInHand") < index(hud, GAME, "integrate3DHudDepth"));
        assertTrue(fieldReads(hud).containsAll(List.of("hud3DTarget", "mainRenderTarget")),
                "both consistentDepthRequired branches must remain visible to the clear redirect");
    }

    @Test
    void handHookForwardsTheActualClearOperandAndPreservesTheClear() {
        MethodNode hook = method(MIXIN, "metallum$preserveWorldDepthBeforeHand");
        var capture = calls(hook, "com/metallum/client/metal/render/MetalFxManager", "preserveWorldDepthBeforeHand").getFirst();
        var clear = calls(hook, ENCODER, "clearDepthTexture").getFirst();
        assertEquals(CLEAR, clear.desc);
        assertTrue(hook.instructions.indexOf(capture) < hook.instructions.indexOf(clear));
        assertEquals(2, ((VarInsnNode) previousOpcode(capture)).var, "capture the supplied depth argument");
        AbstractInsnNode depthLoad = previousOpcode(previousOpcode(clear));
        assertEquals(Opcodes.ALOAD, depthLoad.getOpcode());
        assertEquals(2, ((VarInsnNode) depthLoad).var, "clear the same depth argument, not a main-target lookup");
        assertTrue(calls(hook, GAME, "mainRenderTarget").isEmpty());
    }

    @Test
    void worldPostEffectsGuiAndDepthClearOrderingRemainsExplicit() {
        MethodNode render = method(GAME, "render");
        assertTrue(index(render, GAME, "renderLevel") < index(render, GAME, "applyPostEffects"));
        int clear = index(render, ENCODER, "clearDepthTexture");
        assertTrue(index(render, GAME, "applyPostEffects") < clear);
        assertTrue(clear < index(render, "net/minecraft/client/gui/render/GuiRenderer", "render"));
        MethodNode world = method(GAME, "renderLevel");
        assertTrue(index(world, LEVEL, "render") < index(world, GAME, "render3dHud"));
        assertTrue(index(world, "org/joml/Matrix4f", "mul")
                < index(world, "net/minecraft/client/renderer/ProjectionMatrixBuffer", "getBuffer"));
    }

    @Test
    void stagedCpuGeometryIsCapturedBeforeUploadReleasesItsSlices() {
        MethodNode upload = method("net/minecraft/client/renderer/StagedVertexBuffer", "uploadDrawsToBuffers");
        assertTrue(index(upload, "java/nio/ByteBuffer", "put")
                < index(upload, "net/minecraft/client/renderer/StagedVertexBuffer$Draw", "freeVertexData"));
        MethodNode release = method("net/minecraft/client/renderer/StagedVertexBuffer$Draw", "freeVertexData");
        assertFalse(calls(release, "java/util/List", "clear").isEmpty());
        MethodNode end = method("net/minecraft/client/renderer/StagedVertexBuffer", "endDraw");
        assertTrue(fieldWrites(end).containsAll(List.of("currentVertexBuffer", "currentIndexBuffer")));
    }

    @Test
    void cutoutMrtDeclarationAndPipelineAreWiredBeforeRenderPearlValidation() {
        assertFalse(calls(method("com/metallum/mixin/render/FrontendCommandEncoderMetalFxMixin",
                "metallum$declareCutoutMrt"), "com/metallum/client/metal/render/MetalFxManager",
                "withCutoutReactiveAttachment").isEmpty());
        assertFalse(calls(method("com/metallum/mixin/render/FrontendRenderPassBackendAccessMixin",
                "metallum$matchCutoutMrt"), "com/metallum/client/metal/render/MetalFxReactivePass",
                "adaptPipeline").isEmpty());
        MethodNode gate = method("com/metallum/client/metal/render/MetalFxManager", "usesCutoutReactiveTerrain");
        assertFalse(calls(gate, "com/metallum/client/metal/render/MetalFxReactivePass$Source", "acceptsNewPasses").isEmpty());
        MethodNode receipt = method("com/metallum/client/metal/render/MetalFxReactivePass$Pass", "didEncode");
        assertTrue(fieldWrites(receipt).contains("encodedDrawBatches"));
        assertTrue(fieldWrites(receipt).contains("incompleteCoverage"));
        for (String name : List.of("decorate", "claim", "acceptsNewPasses")) {
            assertFalse(fieldWrites(method("com/metallum/client/metal/render/MetalFxReactivePass$Source", name))
                    .contains("encodedDrawBatches"), "only an encoded draw may produce a receipt");
        }
        var manager = load("com/metallum/client/metal/render/MetalFxManager");
        assertTrue(manager.methods.stream().noneMatch(m -> m.name.equals("cutoutReactiveAttachment")));
        for (var m : manager.methods) {
            assertFalse(strings(m).contains("metallum.metalfx.cutoutReactiveTerrain"),
                    "a launch flag must not substitute for the actual pass contract");
        }
    }

    private static ClassNode load(String owner) {
        try (InputStream input = MinecraftMetalFxSourceContractTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(input, owner + " missing from the compile dependency");
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException e) {
            throw new AssertionError(owner, e);
        }
    }
    private static MethodNode method(String owner, String name) {
        var methods = load(owner).methods.stream().filter(m -> m.name.equals(name)).toList();
        assertEquals(1, methods.size(), owner + "." + name + " changed shape");
        return methods.getFirst();
    }
    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name) {
        var result = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) result.add(call);
        }
        return result;
    }
    private static int index(MethodNode method, String owner, String name) {
        var found = calls(method, owner, name);
        assertFalse(found.isEmpty(), owner + "." + name + " absent from " + method.name);
        return method.instructions.indexOf(found.getFirst());
    }
    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode result = instruction.getPrevious();
        while (result != null && result.getOpcode() < 0) result = result.getPrevious();
        assertNotNull(result);
        return result;
    }
    private static List<String> strings(MethodNode method) {
        var result = new ArrayList<String>();
        for (var node : method.instructions) if (node instanceof LdcInsnNode ldc && ldc.cst instanceof String s) result.add(s);
        return result;
    }
    private static List<String> fieldReads(MethodNode method) { return fields(method, Opcodes.GETFIELD); }
    private static List<String> fieldWrites(MethodNode method) { return fields(method, Opcodes.PUTFIELD); }
    private static List<String> fields(MethodNode method, int opcode) {
        var result = new ArrayList<String>();
        for (var node : method.instructions) if (node instanceof FieldInsnNode f && f.getOpcode() == opcode) result.add(f.name);
        return result;
    }
}
