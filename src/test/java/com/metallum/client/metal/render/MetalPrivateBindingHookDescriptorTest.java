package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the bytecode call sites consumed by the P1c token-native redirectors.
 *
 * <p>Mixin's defaultRequire catches a stale redirect when a client applies the
 * mixin. This test moves that failure into ordinary {@code gradlew test}, where
 * dependency upgrades can name the exact Sodium/Iris call shape that changed.
 * The Sodium checks also pin the literal resource names: the private path uses
 * pre-resolved tokens, so retaining a call descriptor while renaming a resource
 * must fail here instead of pairing an old token with a new compatibility key.</p>
 */
final class MetalPrivateBindingHookDescriptorTest {
    @Test
    void inspectPinnedShaderIncludeApi() throws Exception {
        String resource="com/mojang/renderpearl/api/pipeline/ShaderSource$CachedIncludeSource";
        ClassNode type=load(resource);
        byte[] bytes;
        try(var input=getClass().getClassLoader().getResourceAsStream(resource+".class")){bytes=input.readAllBytes();}
        var report=new java.util.LinkedHashMap<String,Object>();
        report.put("sourceCommit",System.getenv().getOrDefault("METALLUM_SOURCE_SHA","local-unrecorded"));
        report.put("resource",resource);
        report.put("classSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        report.put("fields",type.fields.stream().map(field->field.name+":"+field.desc).toList());
        report.put("methods",type.methods.stream().map(method->method.name+method.desc).toList());
        report.put("sourceAdmissionGranted",false);
        report.put("boundary","Pinned include API signatures only; shader identifiers and namespace do not prove source semantics.");
        report.put("assetReference",MetalMotionShaderAssetAudit.inspect());
        ClassNode includeResult=load("org/lwjgl/util/shaderc/ShadercIncludeResult");
        report.put("includeResultMethods",includeResult.methods.stream().map(method->method.name+method.desc).toList());
        var compressed=new java.io.ByteArrayOutputStream();
        try(var gzip=new java.util.zip.GZIPOutputStream(compressed)){gzip.write(new com.google.gson.Gson().toJson(report).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        System.out.println("METAL_SHADER_SOURCE_API_GZIP_BASE64 "+java.util.Base64.getEncoder().encodeToString(compressed.toByteArray()));
    }

    @Test
    void inspectPinnedCullRecoveryBoundaryWithoutPromotingTheOldOptimization() throws Exception {
        String resource="net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager";
        ClassNode type=load(resource);
        var methods=new java.util.LinkedHashMap<String,Object>();
        for(var method:type.methods)if(method.name.equals("prepareRenderTrees") || method.name.equals("consumeCullTaskResults")
                || method.name.equals("scheduleAsyncWork")) {
            var rows=new java.util.ArrayList<String>();int index=0;
            var labels=new java.util.IdentityHashMap<org.objectweb.asm.tree.LabelNode,Integer>();
            for(var instruction:method.instructions)if(instruction instanceof org.objectweb.asm.tree.LabelNode label)labels.put(label,index++);
            for(var instruction:method.instructions) {
                String row=Integer.toString(instruction.getOpcode());
                if(instruction instanceof MethodInsnNode call)row+=" call "+call.owner+"."+call.name+call.desc;
                else if(instruction instanceof org.objectweb.asm.tree.FieldInsnNode field)row+=" field "+field.owner+"."+field.name+":"+field.desc;
                else if(instruction instanceof org.objectweb.asm.tree.JumpInsnNode jump)row+=" jump L"+labels.get(jump.label);
                else if(instruction instanceof org.objectweb.asm.tree.LabelNode label)row="L"+labels.get(label);
                else if(instruction instanceof org.objectweb.asm.tree.VarInsnNode variable)row+=" local "+variable.var;
                else if(instruction instanceof LdcInsnNode literal)row+=" constant "+literal.cst;
                rows.add(row);
            }
            methods.put(method.name+method.desc,rows);
        }
        byte[] bytes;
        try(var input=getClass().getClassLoader().getResourceAsStream(resource+".class")){bytes=input.readAllBytes();}
        var report=new java.util.LinkedHashMap<String,Object>();
        report.put("sourceCommit",System.getenv().getOrDefault("METALLUM_SOURCE_SHA","local-unrecorded"));
        report.put("dependencyClassSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        report.put("resource",resource);report.put("fields",type.fields.stream().map(field->field.name+":"+field.desc).toList());
        report.put("methods",methods);report.put("sameCancellationSemanticsProven",false);
        report.put("promotionAllowed",false);report.put("boundary","Exact pinned dependency bytecode for review. Hook shape alone is not a cancellation-path proof; no old optimization is enabled.");
        byte[] json=new com.google.gson.Gson().toJson(report).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var compressed=new java.io.ByteArrayOutputStream();try(var gzip=new java.util.zip.GZIPOutputStream(compressed)){gzip.write(json);}
        System.out.println("SODIUM_CULL_REVIEW_GZIP_BASE64 "+java.util.Base64.getEncoder().encodeToString(compressed.toByteArray()));
    }

    @Test
    void sodiumCloudBulkEmitterIsCoveredAtItsFinalByteBufferBoundary() throws Exception {
        String name="net/caffeinemc/mods/sodium/mixin/features/render/world/clouds/CloudRendererMixin";
        ClassNode cloud=load(name);
        MethodNode build=cloud.methods.stream().filter(method->method.name.equals("buildMesh")
                && method.desc.contains("Ljava/nio/ByteBuffer;")).findFirst().orElseThrow();
        boolean directByteWrites=false;
        int ordinaryFaceCalls=0;
        var pending=new java.util.ArrayDeque<MethodNode>();pending.add(build);
        var seen=new java.util.HashSet<String>();
        while(!pending.isEmpty()) {
            MethodNode method=pending.remove();if(!seen.add(method.name+method.desc))continue;
            for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode invoke) {
                if((invoke.owner.endsWith("/MemoryIntrinsics") && invoke.name.equals("putByte"))
                        || (invoke.owner.equals("org/lwjgl/system/MemoryUtil") && invoke.name.equals("memPutByte")))directByteWrites=true;
                if(invoke.name.equals("encodeFace"))ordinaryFaceCalls++;
                if(invoke.owner.equals(cloud.name))cloud.methods.stream()
                        .filter(candidate->candidate.name.equals(invoke.name) && candidate.desc.equals(invoke.desc)).forEach(pending::add);
            }
        }
        assertTrue(directByteWrites,"Review the exact pinned Sodium producer if its bulk cloud emission changes");
        assertTrue(java.util.stream.StreamSupport.stream(build.instructions.spliterator(),false)
                .filter(instruction->instruction instanceof MethodInsnNode).map(instruction->(MethodInsnNode)instruction)
                .anyMatch(invoke->invoke.owner.equals("java/nio/ByteBuffer") && invoke.name.equals("position") && invoke.desc.startsWith("(I)")),
                "The common capture relies on the actual completed buffer extent");
        try(InputStream input=getClass().getClassLoader().getResourceAsStream(name+".class")) {
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            System.out.println("SODIUM_CLOUD_MESH_CONTRACT classSha256="+digest+" bulkWrites=true ordinaryFaceCalls="+ordinaryFaceCalls
                    +" buildMesh="+build.desc);
        }
    }

    private static final String RENDER_PASS = "com/mojang/renderpearl/api/commands/RenderPass";
    private static final String METAL_RENDER_PASS = "com/metallum/client/metal/render/MetalRenderPass";
    private static final String SET_UNIFORM_SLICE =
            "(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V";
    private static final String SET_UNIFORM_BUFFER =
            "(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;)V";
    private static final String BIND_TEXTURE =
            "(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
                    + "Lcom/mojang/renderpearl/api/textures/GpuSampler;)V";
    private static final String BIND_STORAGE_IMAGE =
            "(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V";
    private static final String MULTI_DRAW_BATCH =
            "net/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch";
    private static final String DRAW_CONTEXT =
            "(Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;)V";
    private static final String VK_INDIRECT_BATCH =
            "net/caffeinemc/mods/sodium/client/gpu/device/batch/VKIndirectDrawBatch";
    private static final String DRAW_INDEXED_INDIRECT =
            "(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;I)V";

    @Test
    void sodiumTerrainStillHasTheFourFixedBindingCallSites() {
        ClassNode sodium = load("net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer");
        List<MethodNode> renderMethods = sodium.methods.stream()
                .filter(method -> method.name.equals("render"))
                .filter(method -> (method.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) == 0)
                .toList();
        assertEquals(1, renderMethods.size(),
                "DefaultChunkRenderer.render changed shape; review the P1c Sodium redirects");
        MethodNode render = renderMethods.getFirst();

        assertEquals(List.of("u_Globals"),
                invocationStringKeys(render, RENDER_PASS, "setUniform", SET_UNIFORM_SLICE),
                "the GpuBufferSlice terrain binding is no longer the fixed u_Globals resource");
        assertEquals(List.of("u_SectionTimeInfo"),
                invocationStringKeys(render, RENDER_PASS, "setUniform", SET_UNIFORM_BUFFER),
                "the GpuBuffer terrain binding is no longer the fixed u_SectionTimeInfo resource");
        assertEquals(List.of("u_LightTex", "u_BlockTex"),
                invocationStringKeys(render, RENDER_PASS, "setUniform", BIND_TEXTURE),
                "the two texture ordinals no longer map to u_LightTex then u_BlockTex");
    }

    @Test
    void sodiumTerrainProducerStillHasOneBatchDrawBoundary() {
        ClassNode sodium = load("net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer");
        MethodNode render = sodium.methods.stream()
                .filter(method -> method.name.equals("render"))
                .filter(method -> (method.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) == 0)
                .findFirst()
                .orElseThrow();

        assertEquals(1, invocationCount(render, MULTI_DRAW_BATCH, "draw", DRAW_CONTEXT),
                "terrain snapshot scope must wrap Sodium's single producer batch call");
    }

    @Test
    void sodiumIndirectProducerStillOwnsTheSingleUploadedCommandCall() {
        ClassNode indirect = load(VK_INDIRECT_BATCH);
        MethodNode draw = indirect.methods.stream()
                .filter(method -> method.name.equals("draw"))
                .filter(method -> method.desc.contains("DrawContext"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, invocationCount(draw, RENDER_PASS, "drawIndexedIndirect", DRAW_INDEXED_INDIRECT),
                "VKIndirectDrawBatch.draw must keep one RenderPass indirect submission");
        FieldNode commands = indirect.fields.stream()
                .filter(field -> field.name.equals("pCommands"))
                .findFirst()
                .orElseThrow();
        assertEquals("J", commands.desc);
        assertTrue((commands.access & Opcodes.ACC_PRIVATE) != 0);
        assertTrue((commands.access & Opcodes.ACC_FINAL) != 0);

    }

    @Test
    void irisRasterBindingLoopStillMatchesTheTokenCursorContract() {
        ClassNode postChain = load("com/metallum/client/metal/render/IrisMetalPostChain");
        List<MethodNode> methods = postChain.methods.stream()
                .filter(method -> method.name.equals("bindResources"))
                .toList();
        assertEquals(1, methods.size(),
                "IrisMetalPostChain.bindResources changed shape; review the token cursor before shipping");
        MethodNode bindResources = methods.getFirst();

        assertEquals(2, invocationCount(bindResources, METAL_RENDER_PASS, "setUniform", SET_UNIFORM_SLICE),
                "the token cursor expects one uniform-loop and one texel-buffer setUniform call site");
        assertEquals(1, invocationCount(bindResources, METAL_RENDER_PASS, "bindTexture", BIND_TEXTURE),
                "the token cursor expects exactly one sampled-texture binding call site");
        assertEquals(1, invocationCount(bindResources, METAL_RENDER_PASS, "bindStorageImage", BIND_STORAGE_IMAGE),
                "the token cursor expects exactly one storage-image binding call site");
    }

    private static List<String> invocationStringKeys(
            final MethodNode method,
            final String owner,
            final String name,
            final String descriptor
    ) {
        List<String> keys = new ArrayList<>();
        String lastStringConstant = null;
        for (var instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                lastStringConstant = value;
            }
            if (instruction instanceof MethodInsnNode invoke
                    && invoke.owner.equals(owner)
                    && invoke.name.equals(name)
                    && invoke.desc.equals(descriptor)) {
                if (lastStringConstant == null) {
                    throw new AssertionError(
                            "binding invocation " + owner + "." + name + descriptor
                                    + " no longer has a preceding String constant"
                    );
                }
                keys.add(lastStringConstant);
                lastStringConstant = null;
            }
        }
        return keys;
    }

    private static int invocationCount(
            final MethodNode method,
            final String owner,
            final String name,
            final String descriptor
    ) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invoke
                    && invoke.owner.equals(owner)
                    && invoke.name.equals(name)
                    && invoke.desc.equals(descriptor)) {
                count++;
            }
        }
        return count;
    }

    private static ClassNode load(final String internalName) {
        String resource = internalName + ".class";
        ClassLoader loader = MetalPrivateBindingHookDescriptorTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError(resource + " is not on the test classpath");
            }
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException exception) {
            throw new AssertionError("Failed to inspect " + resource, exception);
        }
    }
}
