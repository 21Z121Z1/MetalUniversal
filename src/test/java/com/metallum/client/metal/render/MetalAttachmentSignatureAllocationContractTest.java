package com.metallum.client.metal.render;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the render-thread attachment-signature lookup to its allocation-reduced form. */
final class MetalAttachmentSignatureAllocationContractTest {
    private static final String PIPELINE =
            "com/metallum/client/metal/render/MetalCompiledRenderPipeline";
    private static final String RENDER_PASS =
            "com/metallum/client/metal/render/MetalRenderPass";
    private static final String PIXEL_FORMAT =
            "Lcom/metallum/client/metal/render/mtl/MTLPixelFormat;";
    private static final String SIGNATURE =
            "Lcom/metallum/client/metal/render/MetalCompiledRenderPipeline$PipelineSignature;";

    @Test
    void signatureLookupReusesPrebuiltColorFormatView() throws IOException {
        ClassNode pipeline = readClass(PIPELINE);
        assertTrue(pipeline.fields.stream().anyMatch(field ->
                        "colorFormatsView".equals(field.name)
                                && "Ljava/util/List;".equals(field.desc)),
                "compiled pipelines must retain one reusable immutable color-format view");

        MethodNode signatureFor = requireMethod(
                pipeline,
                "signatureFor",
                "(" + PIXEL_FORMAT + PIXEL_FORMAT + ")" + SIGNATURE
        );

        assertEquals(0, countCalls(signatureFor, "java/util/Arrays", "asList"),
                "pipeline signature lookup rebuilt an Arrays.asList wrapper");
        assertEquals(0, countCalls(signatureFor, "java/util/List", "copyOf"),
                "pipeline signature lookup copied the color-format collection");
        assertTrue(countFieldReads(signatureFor, PIPELINE, "colorFormatsView") >= 1,
                "pipeline signature lookup does not reuse colorFormatsView");
    }

    @Test
    void attachmentCompatibilityCanBeCheckedWithoutExposingOrCloningFormats() throws IOException {
        ClassNode pipeline = readClass(PIPELINE);
        MethodNode matches = requireMethod(
                pipeline,
                "matchesColorAttachmentFormats",
                "([" + PIXEL_FORMAT + ")Z"
        );
        assertEquals(1, countCalls(matches, "java/util/Arrays", "equals"),
                "attachment compatibility should use one direct array comparison");
        assertEquals(0, countCalls(matches, "[Lcom/metallum/client/metal/render/mtl/MTLPixelFormat;", "clone"),
                "attachment compatibility must not clone the compiled format array");
    }

    @Test
    void renderPassCachesFormatsAndUsesTheNoCopyCompatibilityGate() throws IOException {
        ClassNode pass = readClass(RENDER_PASS);
        assertTrue(pass.fields.stream().anyMatch(field ->
                        "colorAttachmentFormats".equals(field.name)
                                && ("[" + PIXEL_FORMAT).equals(field.desc)),
                "render passes must retain one attachment-format snapshot");

        MethodNode setPipeline = requireMethod(
                pass,
                "setPipeline",
                "(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
        );
        assertEquals(1, countCalls(setPipeline, PIPELINE, "matchesColorAttachmentFormats"),
                "setPipeline must compare against the cached pass snapshot without cloning");
        assertEquals(0, countCalls(setPipeline, RENDER_PASS, "colorAttachmentFormats"),
                "setPipeline rebuilt or cloned the render-pass attachment signature");

        MethodNode getter = requireMethod(
                pass,
                "colorAttachmentFormats",
                "()[" + PIXEL_FORMAT
        );
        assertEquals(0, countCalls(getter, "com/metallum/client/metal/render/MetalGpuTexture", "mtlPixelFormat"),
                "attachment-format getter still walks textures instead of the cached snapshot");
    }

    private static ClassNode readClass(final String name) throws IOException {
        try (InputStream input = MetalAttachmentSignatureAllocationContractTest.class
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
        MethodNode method = owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst()
                .orElse(null);
        assertNotNull(method, owner.name + "." + name + descriptor + " is missing");
        return method;
    }

    private static int countCalls(final MethodNode method, final String owner, final String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static int countFieldReads(
            final MethodNode method,
            final String owner,
            final String name
    ) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && owner.equals(field.owner)
                    && name.equals(field.name)) {
                count++;
            }
        }
        return count;
    }
}
