package com.metallum.mixin.storage;

import com.metallum.client.storage.ChunkSaveSkipPolicy;
import com.metallum.client.storage.ChunkStorageOptions;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Skips an integrated-world chunk rewrite only when the existing NBT is
 * semantically identical outside the root LastUpdate timestamp.
 *
 * <p>This implementation is intentionally conservative and fail-closed. It
 * uses Minecraft's own region decompressor and NBT parser. Any uncertainty
 * falls through to the original write path.
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageSaveSkipMixin {
    @Shadow
    protected abstract RegionFile getRegionFile(ChunkPos pos, boolean create) throws IOException;

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void metallum$skipEquivalentChunkWrite(ChunkPos pos, CompoundTag value, CallbackInfo ci) {
        if (!ChunkStorageOptions.chunkSaveSkipEnabled()
                || value == null
                || SharedConstants.DEBUG_DONT_SAVE_WORLD) {
            return;
        }

        try {
            RegionFile region = this.getRegionFile(pos, false);
            if (region == null || !region.hasChunk(pos)) {
                return;
            }

            try (DataInputStream input = region.getChunkDataInputStream(pos)) {
                if (input == null) {
                    return;
                }

                CompoundTag stored = NbtIo.read(input);
                if (ChunkSaveSkipPolicy.equivalentExceptLastUpdate(stored, value)) {
                    ci.cancel();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Fail closed: vanilla performs the write when equivalence cannot be proven.
        }
    }
}
