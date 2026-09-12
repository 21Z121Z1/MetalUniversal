package com.metallum.client.metal.render;

import net.minecraft.client.renderer.blockentity.state.BannerRenderState;
import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;
import net.minecraft.client.renderer.blockentity.state.BellRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.blockentity.state.ShulkerBoxRenderState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalBlockEntityExactMotionTest {
    @Test
    void onlyExactAuditedVanillaStateClassesAreAdmitted() {
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(ChestRenderState.class));
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(BannerRenderState.class));
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(ShulkerBoxRenderState.class));
        assertTrue(MetalBlockEntityExactMotion.supportsStateClass(BellRenderState.class));
        assertFalse(MetalBlockEntityExactMotion.supportsStateClass(BeaconRenderState.class));
        assertFalse(MetalBlockEntityExactMotion.supportsStateClass(BlockEntityRenderState.class));
    }

    @Test
    void blockPositionIdentityIsStableAndNamespaced() {
        long first = MetalBlockEntityExactMotion.objectId(0x1234_5678_9ABCDEFL);
        assertEquals(first, MetalBlockEntityExactMotion.objectId(0x1234_5678_9ABCDEFL));
        assertNotEquals(first, MetalBlockEntityExactMotion.objectId(0x1234_5678_9ABCDF0L));
    }
}
