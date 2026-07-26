package com.metallum.client.metal.render;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

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
