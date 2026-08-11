package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainMeshGeneration;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stamps a mesh with the generation observed when its worker task began. */
@Mixin(ChunkBuilderMeshingTask.class)
public abstract class TerrainChunkBuilderMeshingGenerationMixin {
    @Unique
    private long metallum$terrainGeneration = TerrainMeshGeneration.UNSTAMPED;

    @Inject(
            method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
                    + "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            at = @At("HEAD"),
            remap = false
    )
    private void metallum$captureTerrainGeneration(
            final ChunkBuildContext context,
            final CancellationToken cancellationToken,
            final CallbackInfo ci
    ) {
        this.metallum$terrainGeneration = TerrainMeshGeneration.current();
    }

    @Inject(
            method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
                    + "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            at = @At("RETURN"),
            remap = false
    )
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
