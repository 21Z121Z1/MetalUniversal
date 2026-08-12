package com.metallum.client.metal.render;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents Iris uniform token lookup from regressing to linear block scans. */
final class IrisMetalUniformTokenLookupContractTest {
    private static final String VALUES = "com/metallum/client/metal/render/IrisMetalUniformValues";

    @Test
    void tokenLookupUsesTheRegistrationIndex() throws IOException {
        ClassNode values = readClass(VALUES);
        FieldNode index = values.fields.stream()
                .filter(field -> "blocksByToken".equals(field.name))
                .findFirst()
                .orElse(null);
        assertNotNull(index, "Iris uniform values must retain an O(1) token index");

        MethodNode slice = requireMethod(values, "slice");
        MethodNode find = requireMethod(values, "findBlock");
        assertTrue(countCalls(slice, "java/util/Map", "get") >= 1,
                "slice(token) must resolve through blocksByToken");
        assertTrue(countCalls(find, "java/util/Map", "get") >= 1,
                "findBlock(token) must resolve through blocksByToken");
        assertEquals(0, countCalls(slice, "java/util/List", "iterator"),
                "slice(token) regressed to a linear list scan");
        assertEquals(0, countCalls(find, "java/util/List", "iterator"),
                "findBlock(token) regressed to a linear list scan");
    }

    @Test
    void registrationAndCloseMaintainBothViews() throws IOException {
        ClassNode values = readClass(VALUES);
        MethodNode register = requireMethod(values, "register");
        MethodNode close = requireMethod(values, "close");
        assertTrue(countCalls(register, "java/util/Map", "get") >= 1,
                "register must reject conflicting duplicate tokens through the index");
        assertTrue(countCalls(register, "java/util/Map", "put") >= 1,
                "register must publish new blocks into the token index");
        assertTrue(countCalls(close, "java/util/Map", "clear") >= 1,
                "close must clear the token index with the owning block list");
    }

    private static ClassNode readClass(final String name) throws IOException {
        try (InputStream input = IrisMetalUniformTokenLookupContractTest.class
                .getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name + " bytecode is missing from the test runtime classpath");
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode requireMethod(final ClassNode owner, final String name) {
        MethodNode method = owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name))
                .findFirst()
                .orElse(null);
        assertNotNull(method, owner.name + "." + name + " is missing");
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
}
