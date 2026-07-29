package com.metallum.client.metal.render;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the motion hooks still describe methods Minecraft actually has.
 *
 * <p>Mixin resolves an injection target from a descriptor string. With
 * {@code defaultRequire} at 1 a stale descriptor is fatal rather than quiet, but it
 * is fatal during client startup — after the build went green and after a release
 * could have been cut. Reading the signature back out of the Minecraft classes on
 * the test classpath moves that failure to {@code ./gradlew test} and names the
 * method whose shape changed.</p>
 *
 * <p>Classes are loaded without initialisation on purpose. Reflection over members
 * does not need a static initialiser to have run, and Minecraft's would expect a
 * game environment that does not exist here.</p>
 */
final class MetalMotionHookDescriptorTest {
    private static Class<?> load(final String binaryName) {
        try {
            return Class.forName(binaryName, false, MetalMotionHookDescriptorTest.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            throw new AssertionError(binaryName + " is not on the test classpath, so the motion hooks"
                    + " cannot be checked against it", absent);
        }
    }

    private static String descriptorOf(final Class<?>[] parameterTypes, final Class<?> returnType) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameterType : parameterTypes) {
            descriptor.append(typeDescriptor(parameterType));
        }
        return descriptor.append(')').append(typeDescriptor(returnType)).toString();
    }

    private static String typeDescriptor(final Class<?> type) {
        if (type.isArray()) {
            return "[" + typeDescriptor(type.getComponentType());
        }
        if (!type.isPrimitive()) {
            return "L" + type.getName().replace('.', '/') + ";";
        }
        return switch (type.getName()) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> throw new AssertionError("unknown primitive " + type.getName());
        };
    }

    private static Method assertMethod(
            final Class<?> owner,
            final String name,
            final String expectedDescriptor,
            final boolean includeInherited
    ) {
        Method[] methods = includeInherited ? owner.getMethods() : owner.getDeclaredMethods();
        List<Method> candidates = Arrays.stream(methods)
                .filter(method -> method.getName().equals(name))
                .filter(method -> descriptorOf(method.getParameterTypes(), method.getReturnType())
                        .equals(expectedDescriptor))
                .toList();
        assertEquals(1, candidates.size(), owner.getName() + "." + name
                + " does not have the expected descriptor " + expectedDescriptor + ": " + candidates);
        return candidates.getFirst();
    }

    private static Constructor<?> assertConstructor(
            final Class<?> owner,
            final String expectedDescriptor
    ) {
        List<Constructor<?>> candidates = Arrays.stream(owner.getDeclaredConstructors())
                .filter(constructor -> descriptorOf(constructor.getParameterTypes(), void.class)
                        .equals(expectedDescriptor))
                .toList();
        assertEquals(1, candidates.size(), owner.getName()
                + " does not have the expected constructor descriptor " + expectedDescriptor
                + ": " + Arrays.toString(owner.getDeclaredConstructors()));
        return candidates.getFirst();
    }

    private static void assertSingleInvokeTarget(
            final String className,
            final String methodName,
            final String methodDescriptor,
            final String expectedTarget
    ) {
        String resource = "/" + className.replace('.', '/') + ".class";
        List<String> targets = new java.util.ArrayList<>();
        try (InputStream classBytes = MetalMotionHookDescriptorTest.class.getResourceAsStream(resource)) {
            if (classBytes == null) {
                throw new AssertionError(resource + " is not on the test classpath");
            }
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                        final int access,
                        final String name,
                        final String descriptor,
                        final String signature,
                        final String[] exceptions
                ) {
                    if (!name.equals(methodName) || !descriptor.equals(methodDescriptor)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(
                                final int opcode,
                                final String owner,
                                final String name,
                                final String descriptor,
                                final boolean isInterface
                        ) {
                            if (name.equals(MetalMotionHooks.GET_VERTEX_BUILDER_NAME)
                                    && descriptor.equals(MetalMotionHooks.GET_VERTEX_BUILDER_DESCRIPTOR)) {
                                targets.add("L" + owner + ";" + name + descriptor);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException readFailure) {
            throw new AssertionError("Could not inspect " + resource, readFailure);
        }
        assertEquals(List.of(expectedTarget), targets,
                className + "." + methodName + " does not invoke the exact owner targeted by its mixin");
    }

    @Test
    void tesselateBlockStillHasTheDescriptorTheRedirectTargets() {
        Class<?> renderer = load(MetalMotionHooks.MODEL_BLOCK_RENDERER_CLASS);
        List<Method> candidates = Arrays.stream(renderer.getDeclaredMethods())
                .filter(method -> method.getName().equals(MetalMotionHooks.TESSELATE_BLOCK_NAME))
                .toList();

        assertEquals(1, candidates.size(),
                "the redirect targets " + MetalMotionHooks.TESSELATE_BLOCK_NAME + " by descriptor, and "
                        + renderer.getName() + " now declares " + candidates.size() + " overloads of it: "
                        + candidates);
        Method tesselateBlock = candidates.getFirst();
        assertEquals(MetalMotionHooks.TESSELATE_BLOCK_DESCRIPTOR,
                descriptorOf(tesselateBlock.getParameterTypes(), tesselateBlock.getReturnType()),
                "MovingBlockFeatureRendererMetalFxMixin's redirect would no longer resolve; update the"
                        + " constant and the handler's parameter list together");
    }

    @Test
    void theMovingBlockSubmitConstructorStillHasTheShapeTheInjectionDeclares() {
        Class<?> submit = load(MetalMotionHooks.MOVING_BLOCK_SUBMIT_CLASS);
        Constructor<?>[] constructors = submit.getDeclaredConstructors();

        assertEquals(1, constructors.length,
                submit.getName() + " now declares " + constructors.length + " constructors, so the"
                        + " <init> injection is ambiguous: " + Arrays.toString(constructors));
        assertEquals(MetalMotionHooks.MOVING_BLOCK_SUBMIT_DESCRIPTOR,
                descriptorOf(constructors[0].getParameterTypes(), void.class),
                "MovingBlockSubmitMetalFxMixin's <init> injection declares a different parameter list"
                        + " than the record now has; the owner would stop being recorded");
    }

    @Test
    void blockEntityRendererSubmitStillHasTheDispatcherWrapperDescriptor() {
        Class<?> renderer = load(MetalMotionHooks.BLOCK_ENTITY_RENDERER_CLASS);
        List<Method> candidates = Arrays.stream(renderer.getDeclaredMethods())
                .filter(method -> method.getName().equals("submit"))
                .toList();

        assertEquals(1, candidates.size(),
                renderer.getName() + " now declares " + candidates.size()
                        + " submit overloads; the dispatcher wrapper would be ambiguous: " + candidates);
        Method submit = candidates.getFirst();
        assertEquals(MetalMotionHooks.BLOCK_ENTITY_SUBMIT_DESCRIPTOR,
                descriptorOf(submit.getParameterTypes(), submit.getReturnType()),
                "BlockEntityRenderDispatcherMetalFxMixin's wrapper no longer matches the real renderer"
                        + " submission method");
    }

    @Test
    void extendedRendererCallSitesStillHaveTheDescriptorsTheirWrappersDeclare() {
        assertMethod(
                load(MetalMotionHooks.ITEM_FRAME_RENDERER_CLASS),
                MetalMotionHooks.ITEM_FRAME_SUBMIT_NAME,
                MetalMotionHooks.ITEM_FRAME_SUBMIT_DESCRIPTOR,
                false
        );
        assertMethod(
                load(MetalMotionHooks.BLOCK_MODEL_RENDER_STATE_CLASS),
                "submitWithZOffset",
                MetalMotionHooks.BLOCK_MODEL_SUBMIT_WITH_Z_OFFSET_DESCRIPTOR,
                false
        );
        assertMethod(
                load(MetalMotionHooks.ITEM_STACK_RENDER_STATE_CLASS),
                "submit",
                MetalMotionHooks.ITEM_SUBMIT_DESCRIPTOR,
                false
        );
        assertMethod(
                load(MetalMotionHooks.MAP_RENDERER_CLASS),
                "render",
                MetalMotionHooks.MAP_RENDER_DESCRIPTOR,
                false
        );
        assertMethod(
                load(MetalMotionHooks.END_CRYSTAL_RENDERER_CLASS),
                MetalMotionHooks.END_CRYSTAL_SUBMIT_NAME,
                MetalMotionHooks.END_CRYSTAL_SUBMIT_DESCRIPTOR,
                false
        );
        assertMethod(
                load(MetalMotionHooks.SUBMIT_NODE_COLLECTOR_CLASS),
                "submitModel",
                MetalMotionHooks.SUBMIT_MODEL_DESCRIPTOR,
                true
        );
        assertMethod(
                load(MetalMotionHooks.ENDER_DRAGON_RENDERER_CLASS),
                MetalMotionHooks.CRYSTAL_BEAMS_NAME,
                MetalMotionHooks.CRYSTAL_BEAMS_DESCRIPTOR,
                false
        );
        assertMethod(
                load(MetalMotionHooks.RENDER_TYPE_FEATURE_RENDERER_CLASS),
                MetalMotionHooks.GET_VERTEX_BUILDER_NAME,
                MetalMotionHooks.GET_VERTEX_BUILDER_DESCRIPTOR,
                false
        );
        assertMethod(
                load(MetalMotionHooks.BLOCK_MODEL_FEATURE_RENDERER_CLASS),
                MetalMotionHooks.BUILD_GROUP_METHOD,
                MetalMotionHooks.BUILD_GROUP_DESCRIPTOR,
                false
        );
        assertMethod(
                load(MetalMotionHooks.CUSTOM_FEATURE_RENDERER_CLASS),
                MetalMotionHooks.BUILD_GROUP_METHOD,
                MetalMotionHooks.BUILD_GROUP_DESCRIPTOR,
                false
        );
    }

    @Test
    void featureRendererMixinsTargetTheOwnersStoredInBuildGroupBytecode() {
        assertSingleInvokeTarget(
                MetalMotionHooks.BLOCK_MODEL_FEATURE_RENDERER_CLASS,
                MetalMotionHooks.BUILD_GROUP_METHOD,
                MetalMotionHooks.BUILD_GROUP_DESCRIPTOR,
                MetalMotionHooks.BLOCK_MODEL_GET_VERTEX_BUILDER_TARGET
        );
        assertSingleInvokeTarget(
                MetalMotionHooks.CUSTOM_FEATURE_RENDERER_CLASS,
                MetalMotionHooks.BUILD_GROUP_METHOD,
                MetalMotionHooks.BUILD_GROUP_DESCRIPTOR,
                MetalMotionHooks.CUSTOM_GET_VERTEX_BUILDER_TARGET
        );
    }

    @Test
    void extendedSubmitRecordsStillHaveTheConstructorShapesTheirMixinsDeclare() {
        assertConstructor(
                load(MetalMotionHooks.BLOCK_MODEL_FEATURE_SUBMIT_CLASS),
                MetalMotionHooks.BLOCK_MODEL_FEATURE_SUBMIT_DESCRIPTOR
        );
        assertConstructor(
                load(MetalMotionHooks.CUSTOM_FEATURE_SUBMIT_CLASS),
                MetalMotionHooks.CUSTOM_FEATURE_SUBMIT_DESCRIPTOR
        );
    }

    @Test
    void theWrapperStillHasAMethodToBeScopedTo() {
        Class<?> renderer = load(MetalMotionHooks.MOVING_BLOCK_FEATURE_RENDERER_CLASS);
        List<Method> candidates = Arrays.stream(renderer.getDeclaredMethods())
                .filter(method -> method.getName().equals(MetalMotionHooks.BUILD_GROUP_METHOD))
                .toList();

        assertEquals(1, candidates.size(),
                renderer.getName() + " declares " + candidates.size() + " methods named "
                        + MetalMotionHooks.BUILD_GROUP_METHOD + "; the wrapper scopes itself by that name"
                        + " alone, so zero makes it unplaceable and more than one makes it ambiguous");
    }

    @Test
    void theRedirectTargetIsBuiltFromTheCheckedName() {
        // Guards the composition itself: the target string is what Mixin matches on,
        // and the two halves this test verified are only useful if the target is
        // actually made of them.
        assertTrue(MetalMotionHooks.TESSELATE_BLOCK_TARGET.endsWith(
                        MetalMotionHooks.TESSELATE_BLOCK_NAME + MetalMotionHooks.TESSELATE_BLOCK_DESCRIPTOR),
                "TESSELATE_BLOCK_TARGET no longer ends with the name and descriptor this test checks: "
                        + MetalMotionHooks.TESSELATE_BLOCK_TARGET);
        assertTrue(MetalMotionHooks.TESSELATE_BLOCK_TARGET.startsWith(
                        "L" + MetalMotionHooks.MODEL_BLOCK_RENDERER_CLASS.replace('.', '/') + ";"),
                "TESSELATE_BLOCK_TARGET names a different owner than the class this test loads: "
                        + MetalMotionHooks.TESSELATE_BLOCK_TARGET);
    }
}
