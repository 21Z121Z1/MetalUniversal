package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainMeshGeneration;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stamps a mesh with the exact ABI snapshot observed when its worker began. */
@Mixin(ChunkBuilderMeshingTask.class)
public abstract class TerrainChunkBuilderMeshingGenerationMixin {
    @Unique
    private static final String EXECUTE =
            "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
                    + "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;";

    @Unique
    private TerrainMeshGeneration.Stamp metallum$terrainGeneration =
            TerrainMeshGeneration.UNSTAMPED;

    @Inject(method = EXECUTE, at = @At("HEAD"), remap = false, require = 1)
    private void metallum$captureTerrainGeneration(
            final ChunkBuildContext context,
            final CancellationToken cancellationToken,
            final CallbackInfoReturnable<ChunkBuildOutput> cir
    ) {
        this.metallum$terrainGeneration = TerrainMeshGeneration.current();
    }

    @Inject(method = EXECUTE, at = @At("RETURN"), remap = false, require = 1)
    private void metallum$stampTerrainGeneration(
            final ChunkBuildContext context,
            final CancellationToken cancellationToken,
            final CallbackInfoReturnable<ChunkBuildOutput> cir
    ) {
        ChunkBuildOutput output = cir.getReturnValue();
        if (output instanceof TerrainMeshGeneration.OutputAccess access) {
            access.metallum$setTerrainGeneration(this.metallum$terrainGeneration);
        }
    }
}
