package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainMeshGeneration;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Enforces the worker-to-render-thread generation contract at publication. */
@Mixin(RenderSectionManager.class)
public abstract class RenderSectionManagerTerrainGenerationMixin {
    @Unique
    private static final String APPLY_BUILD_OUTPUTS =
            "applyBuildOutputs(Ljava/util/ArrayList;)Ljava/util/List;";
    @Unique
    private static final String UPDATE_WITH_RESULT =
            "updateWithResult(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;"
                    + "Ljava/util/List;)I";

    @Shadow
    public abstract void markGraphDirty();

    @Redirect(
            method = APPLY_BUILD_OUTPUTS,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;"
                            + "addBuildOutput("
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/BuilderTaskOutput;)Z"
            ),
            remap = false,
            require = 1
    )
    private boolean metallum$rejectStaleTerrainBuild(
            final RenderSection section,
            final BuilderTaskOutput output
    ) {
        if (output instanceof ChunkBuildOutput buildOutput) {
            TerrainMeshGeneration.Stamp stamp =
                    buildOutput instanceof TerrainMeshGeneration.OutputAccess access
                            ? access.metallum$terrainGeneration()
                            : TerrainMeshGeneration.UNSTAMPED;
            if (!TerrainMeshGeneration.acceptsBuild(stamp)) {
                int currentUpdate = section.getPendingUpdate();
                int rebuildUpdate = ChunkUpdateTypes.join(
                        currentUpdate,
                        ChunkUpdateTypes.REBUILD
                );
                if (rebuildUpdate != currentUpdate) {
                    section.setPendingUpdate(rebuildUpdate, System.nanoTime());
                    this.markGraphDirty();
                }
                // processChunkBuilds owns and destroys every collected result
                // after this call. Do not release it here or it will be freed
                // twice by Sodium's outer result loop.
                return false;
            }
        }
        return section.addBuildOutput(output);
    }

    @Inject(method = UPDATE_WITH_RESULT, at = @At("RETURN"), remap = false, require = 1)
    private void metallum$recordPublishedTerrainGeneration(
            final Viewport viewport,
            final RenderSection section,
            final ChunkBuildOutput output,
            final List<RenderSection> pendingPresentPatches,
            final CallbackInfoReturnable<Integer> cir
    ) {
        TerrainMeshGeneration.Stamp stamp =
                output instanceof TerrainMeshGeneration.OutputAccess access
                        ? access.metallum$terrainGeneration()
                        : TerrainMeshGeneration.UNSTAMPED;
        if (section instanceof TerrainMeshGeneration.SectionAccess access) {
            access.metallum$setTerrainGeneration(stamp);
        }
    }
}
