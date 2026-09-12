package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.blockentity.state.BannerRenderState;
import net.minecraft.client.renderer.blockentity.state.BellRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.blockentity.state.ShulkerBoxRenderState;

/**
 * Audited Minecraft 26.2 block-entity families whose complete collector output is entity-format
 * {@code submitModel} geometry and can therefore reuse Metallum's exact staged previous-position
 * history. This is intentionally an exact-class allowlist: a modded subclass may add collector
 * outputs or shader semantics that were never audited and must stay fail-closed.
 */
@Environment(EnvType.CLIENT)
final class MetalBlockEntityExactMotion {
    private static final long OBJECT_NAMESPACE = 0x4D4658424C4F434BL; // ASCII "MFXBLOCK".

    private MetalBlockEntityExactMotion() {
    }

    static boolean supports(final BlockEntityRenderState state) {
        return state != null && supportsStateClass(state.getClass());
    }

    static boolean supportsStateClass(final Class<?> stateClass) {
        return stateClass == ChestRenderState.class
                || stateClass == BannerRenderState.class
                || stateClass == ShulkerBoxRenderState.class
                || stateClass == BellRenderState.class;
    }

    static long objectId(final long packedBlockPos) {
        return mix(packedBlockPos ^ OBJECT_NAMESPACE);
    }

    private static long mix(final long value) {
        long mixed = value;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return mixed;
    }
}
