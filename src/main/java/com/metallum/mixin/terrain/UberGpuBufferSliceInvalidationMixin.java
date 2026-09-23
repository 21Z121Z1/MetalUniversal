package com.metallum.mixin.terrain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.terrain.VanillaTerrainSliceCache;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Invalidate at actual map mutations, including index-only transparency reuploads. */
@Mixin(UberGpuBuffer.class)
abstract class UberGpuBufferSliceInvalidationMixin {
    @WrapOperation(method = "uploadStagedAllocations", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object metallum$allocationChanged(Map<Object, Object> map, Object key, Object value,
                                              Operation<Object> original) {
        Object previous = original.call(map, key, value);
        // Before the upload callback can publish the mesh to extraction.
        VanillaTerrainSliceCache.invalidate(key);
        return previous;
    }

    @WrapOperation(method = "freeAllocation", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object metallum$allocationRemoved(Map<Object, Object> map, Object key, Operation<Object> original) {
        Object previous = original.call(map, key);
        if (previous != null) VanillaTerrainSliceCache.invalidate(key);
        return previous;
    }

    @WrapOperation(method = "close", at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V"))
    private void metallum$allocationsClosed(Map<Object, Object> map, Operation<Void> original) {
        for (Object key : map.keySet()) VanillaTerrainSliceCache.invalidate(key);
        original.call(map);
    }
}
