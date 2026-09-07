package com.metallum.client.metal.render;

import org.joml.Matrix4f;

/** Source-semantic transform helpers for Minecraft's translating piston moving block. */
final class MetalPistonMotion {
    private MetalPistonMotion() {
    }

    /**
     * PistonHeadRenderer submits only the moving block under translate(xOffset, yOffset, zOffset).
     * Every other transform in that block-entity path is constant for the same render-state model and
     * therefore cancels in previous * inverse(current).
     */
    static Matrix4f offsetTransform(final float xOffset, final float yOffset, final float zOffset) {
        return new Matrix4f().translation(xOffset, yOffset, zOffset);
    }
}
