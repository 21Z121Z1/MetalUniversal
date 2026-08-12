package com.metallum.client.metal.render;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins fail-closed texture state and generation-aware buffer deduplication. */
final class MetalRenderPassStateShadowContractTest {
    private static final String PASS = "com/metallum/client/metal/render/MetalRenderPass";
    private static final String BINDING_CACHE =
            "com/metallum/mixin/render/MetalRenderPassBindingCacheMixin";
    private static final String BINDING_STATE = BINDING_CACHE + "$BindingState";
    private static final String UPLOAD_DEDUP =
            "com/metallum/mixin/render/MetalGpuBufferUploadDedupMixin";
    private static final String CALLBACK_INFO =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String GPU_BUFFER_SLICE =
            "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;";
    private static final String GPU_BUFFER =
            "Lcom/mojang/blaze3d/buffers/GpuBuffer;";
    private static final String INJECT_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/injection/Inject;";

    @Test
    void textureUnbindInvalidatesNativeDescriptorState() throws IOException {
        MethodNode method = requireMethod(
                readClass(PASS),
                "bindTexture",
                "(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;"
                        + "Lcom/mojang/blaze3d/textures/GpuSampler;)V"
        );

        assertTrue(countCalls(method, "java/util/HashMap", "remove", "(Ljava/lang/Object;)Ljava/lang/Object;") >= 1,
                "texture unbind must remove the Java shadow binding");
        assertTrue(countCalls(method, PASS, "markDescriptorDirty", "(Ljava/lang/String;)V") >= 2,
                "texture changes and texture unbinds must both invalidate the native descriptor state");
        assertTrue(countCalls(method,
                        "com/metallum/client/metal/render/MetalCommandEncoder",
                        "flushPendingClear",
                        "(Lcom/metallum/client/metal/render/MetalGpuTexture;)V") >= 1,
                "rebinding the same texture must still materialize a pending clear");
        assertTrue(countCalls(method, "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;") >= 1,
                "texture binding must compare the existing pair before allocating a replacement record");
    }

    @Test
    void bufferDeduplicationUsesBackingGeneration() throws IOException {
        ClassNode cache = readClass(BINDING_CACHE);
        MethodNode uniform = requireMethod(
                cache,
                "metallum$deduplicateUniform",
                "(Ljava/lang/String;" + GPU_BUFFER_SLICE + CALLBACK_INFO + ")V"
        );
        MethodNode storage = requireMethod(
                cache,
                "metallum$deduplicateStorageBuffer",
                "(I" + GPU_BUFFER_SLICE + CALLBACK_INFO + ")V"
        );
        assertTrue(countCalls(uniform, BINDING_STATE, "matches", "(" + GPU_BUFFER_SLICE + ")Z") >= 1,
                "uniform deduplication must consult the generation-aware binding state");
        assertTrue(countCalls(storage, BINDING_STATE, "matches", "(" + GPU_BUFFER_SLICE + ")Z") >= 1,
                "storage-buffer deduplication must consult the generation-aware binding state");

        ClassNode state = readClass(BINDING_STATE);
        MethodNode matches = requireMethod(state, "matches", "(" + GPU_BUFFER_SLICE + ")Z");
        MethodNode update = requireMethod(state, "update", "(" + GPU_BUFFER_SLICE + ")V");
        String versionDescriptor = "(" + GPU_BUFFER + ")J";
        assertTrue(countCalls(matches, BINDING_CACHE, "metallum$bindingVersion", versionDescriptor) >= 1,
                "a same-object slice must be rejected after its native backing changes");
        assertTrue(countCalls(update, BINDING_CACHE, "metallum$bindingVersion", versionDescriptor) >= 1,
                "the cached binding identity must capture the current backing generation");
    }

    @Test
    void backingSwapAdvancesTheVersionObservedByTheBindingCache() throws IOException {
        MethodNode observer = requireMethod(
                readClass(UPLOAD_DEDUP),
                "metallum$observeBackingSwap",
                "(Ljava/lang/foreign/MemorySegment;Ljava/nio/ByteBuffer;" + CALLBACK_INFO + ")V"
        );
        AnnotationNode injection = annotations(observer).stream()
                .filter(annotation -> INJECT_ANNOTATION.equals(annotation.desc))
                .findFirst()
                .orElse(null);
        assertNotNull(injection, "MetalGpuBuffer.swapBacking is not observed by the binding-version mixin");
        assertTrue(annotationContains(injection, "swapBacking"),
                "binding-version injection no longer targets swapBacking");
        assertTrue(annotationContains(injection, "TAIL"),
                "binding version must advance only after a successful backing swap");
        assertTrue(countFieldWrites(observer, UPLOAD_DEDUP, "metallum$bindingVersion") >= 1,
                "backing swap does not advance the binding version");
    }

    private static ClassNode readClass(final String name) throws IOException {
        try (InputStream input = MetalRenderPassStateShadowContractTest.class
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

    private static int countFieldWrites(
            final MethodNode method,
            final String owner,
            final String name
    ) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && owner.equals(field.owner)
                    && name.equals(field.name)) {
                count++;
            }
        }
        return count;
    }

    private static List<AnnotationNode> annotations(final MethodNode method) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) {
            result.addAll(method.visibleAnnotations);
        }
        if (method.invisibleAnnotations != null) {
            result.addAll(method.invisibleAnnotations);
        }
        return result;
    }

    private static boolean annotationContains(final Object value, final String expected) {
        if (value instanceof String text) {
            return text.contains(expected);
        }
        if (value instanceof AnnotationNode annotation) {
            return annotationContains(annotation.values, expected);
        }
        if (value instanceof List<?> values) {
            for (Object entry : values) {
                if (annotationContains(entry, expected)) {
                    return true;
                }
            }
        }
        return false;
    }
}
