package com.metallum.client.metal.render;

import com.metallum.Metallum;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.minecraft.client.Minecraft;

/**
 * The Iris-on-Metal world rendering pipeline (B2-1 slice).
 *
 * <p>Iris's own {@code IrisRenderingPipeline} is a GL object graph: it builds
 * GL programs, framebuffers and samplers in its constructor. On the Metal
 * backend that is not adaptable, so {@code Iris.createPipeline} is redirected
 * to this class instead (see {@code IrisPipelineFactoryMixin}). This is the
 * "semantic layer" seam: Iris still owns pack parsing, option handling, the id
 * maps and the render-phase state machine, while the actual GPU work happens
 * through the Metal backend.</p>
 *
 * <p><b>Scope of B2-1.</b> This pipeline does exactly two things beyond the
 * vanilla behaviour it inherits:</p>
 * <ol>
 *   <li>mirrors the {@link WorldRenderingSettings} that
 *       {@code IrisRenderingPipeline}'s constructor sets, most importantly the
 *       extended chunk vertex format — sodium must build terrain meshes with
 *       the attributes the pack's {@code gbuffers_terrain} expects;</li>
 *   <li>activates {@link IrisMetalPipelineOverrides}, so sodium's terrain
 *       pipelines compile from the pack's translated programs.</li>
 * </ol>
 *
 * <p>Everything else — shadows, composite/final, the deferred chain, custom
 * uniforms, entity/particle programs — is inherited from
 * {@link VanillaRenderingPipeline} and therefore behaves exactly as it does
 * with shaders off. That is the honest state of B2-1: terrain is drawn with the
 * pack's gbuffer program and the raw gbuffer0 output goes to the screen; there
 * is no composite pass yet (B2-3).</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalWorldRenderingPipeline extends VanillaRenderingPipeline {
    private final ProgramSet programSet;
    private final ShaderPack pack;
    private final IrisMetalPipelineOverrides.Instance overrides;
    private boolean initializedBlockIds;

    public MetalWorldRenderingPipeline(final ProgramSet programSet) {
        this.programSet = programSet;
        this.pack = programSet.getPack();
        PackDirectives directives = programSet.getPackDirectives();

        // Mirrors IrisRenderingPipeline's constructor. The vertex format is the
        // load-bearing one: FormatAnalyzer.createFormat(true, true, true, true)
        // is the extended (XHFP) chunk format whose extra attributes Iris's own
        // sodium mesh mixins write, and which the patched terrain shader reads.
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
        settings.setEntityIds(this.pack.getIdMap().getEntityIdMap());
        settings.setItemIds(this.pack.getIdMap().getItemIdMap());
        settings.setAmbientOcclusionLevel(directives.getAmbientOcclusionLevel());
        settings.setDisableDirectionalShading(shouldDisableDirectionalShading());
        settings.setUseSeparateAo(directives.shouldUseSeparateAo());
        settings.setBreaksAnisotropy(directives.breaksAnisotropy());
        settings.setVoxelizeLightBlocks(directives.shouldVoxelizeLightBlocks());
        settings.setSeparateEntityDraws(directives.shouldUseSeparateEntityDraws());

        this.overrides = IrisMetalPipelineOverrides.activate(programSet, directives.getTextureMap());
        Metallum.LOGGER.info(
                "[metallum-iris] semantic pipeline generation {} online for pack program set {}",
                this.overrides.generation(), this.pack.getProfileInfo()
        );
    }

    /**
     * Block/tag id maps are built lazily on the first frame, exactly as
     * {@code IrisRenderingPipeline} does — they need a loaded level, and
     * populating them invalidates every chunk mesh, so the rebuild is triggered
     * once here rather than at pack load.
     *
     * <p>{@code super.beginLevelRendering()} is deliberately not called: it
     * issues {@code glClipControl} and {@code glUseProgram} (see
     * {@code IrisVanillaPipelineCompatMixin}), which have no meaning on the
     * Metal backend.</p>
     */
    @Override
    public void beginLevelRendering() {
        // Refresh the pack's uniform block before sodium draws terrain.
        IrisMetalPipelineOverrides.updateFrame();
        if (this.initializedBlockIds) {
            return;
        }
        this.initializedBlockIds = true;
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(
                this.pack.getIdMap().getBlockProperties(), this.pack.getIdMap().getTagEntries()
        ));
        settings.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(
                this.pack.getIdMap().getBlockRenderTypeMap()
        ));
        Minecraft.getInstance().levelExtractor.allChanged();
    }

    @Override
    public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
        return this.programSet.getPackDirectives().getTextureMap();
    }

    @Override
    public float getSunPathRotation() {
        return this.programSet.getPackDirectives().getSunPathRotation();
    }

    @Override
    public boolean shouldDisableDirectionalShading() {
        return !this.programSet.getPackDirectives().isOldLighting();
    }

    @Override
    public void destroy() {
        IrisMetalPipelineOverrides.deactivate();
        Metallum.LOGGER.info(
                "[metallum-iris] semantic pipeline generation {} destroyed", this.overrides.generation()
        );
        super.destroy();
    }
}
