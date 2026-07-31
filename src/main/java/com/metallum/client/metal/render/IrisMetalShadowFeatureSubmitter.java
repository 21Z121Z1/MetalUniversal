package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.ArrayList;

/** Owns feature extraction and submission for one generation-owned shadow scene. */
final class IrisMetalShadowFeatureSubmitter implements AutoCloseable {
    private final Minecraft client;
    private final RenderBuffers renderBuffers;
    private final LevelRenderState levelRenderState = new LevelRenderState();
    private final SubmitNodeStorage submitNodeStorage = new SubmitNodeStorage();
    private final FeatureRenderDispatcher featureRenderDispatcher;
    private boolean closed;

    IrisMetalShadowFeatureSubmitter(final Minecraft client) {
        this.client = client;
        this.renderBuffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
        this.featureRenderDispatcher = new FeatureRenderDispatcher(
                this.renderBuffers,
                client.getModelManager(),
                client.getAtlasManager(),
                client.font,
                client.gameRenderer.gameRenderState()
        );
    }

    RenderBuffers renderBuffers() {
        ensureOpen();
        return this.renderBuffers;
    }

    void prepareCamera(
            final Camera camera,
            final float tickDelta,
            final Matrix4f shadowView,
            final Matrix4f shadowProjection
    ) {
        ensureOpen();
        this.levelRenderState.reset();
        camera.extractRenderState(this.levelRenderState.cameraRenderState, tickDelta);
        this.levelRenderState.cameraRenderState.viewRotationMatrix = shadowView;
        this.levelRenderState.cameraRenderState.projectionMatrix = shadowProjection;
    }

    void submit(
            final LevelRendererAccessor levelRenderer,
            final SodiumWorldRenderer sodium,
            final Camera camera,
            final PoseStack shadowPose,
            final Frustum entityFrustum,
            final Vector3d cameraPosition,
            final PackShadowDirectives shadow,
            final IrisMetalFrameState frameState
    ) {
        ensureOpen();
        if (this.client.level == null) {
            throw new IllegalStateException("Iris Metal shadow feature submission requires a loaded client level");
        }
        EntityRenderDispatcher entityDispatcher = levelRenderer.getEntityRenderDispatcher();
        float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
        frameState.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.ENTITIES);
        try {
            if (shadow.shouldRenderEntities()) {
                extractVisibleEntities(camera, entityFrustum);
            } else if (shadow.shouldRenderPlayer()) {
                extractPlayer(entityDispatcher, this.client.getDeltaTracker().getGameTimeDeltaPartialTick(false));
            }

            for (EntityRenderState state : this.levelRenderState.entityRenderStates) {
                entityDispatcher.submit(
                        state,
                        this.levelRenderState.cameraRenderState,
                        state.x - cameraPosition.x,
                        state.y - cameraPosition.y,
                        state.z - cameraPosition.z,
                        shadowPose,
                        this.submitNodeStorage
                );
            }

            if (shadow.shouldRenderBlockEntities() || shadow.shouldRenderLightBlockEntities()) {
                sodium.extractBlockEntities(
                        camera,
                        tickDelta,
                        this.client.level.destructionProgress(),
                        this.levelRenderState
                );
                if (!shadow.shouldRenderBlockEntities()) {
                    this.levelRenderState.blockEntityRenderStates.removeIf(
                            state -> !shouldRenderLightBlockEntity(
                                    this.client.level.getBlockState(state.blockPos).getLightEmission()
                            )
                    );
                }
                submitBlockEntities(camera, shadowPose);
            }
            this.featureRenderDispatcher.renderAllFeatures(this.submitNodeStorage);
        } finally {
            this.renderBuffers.endFrame();
            frameState.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.NONE);
        }
    }

    private void extractVisibleEntities(final Camera camera, final Frustum frustum) {
        Vec3 cameraPosition = camera.position();
        double cameraX = cameraPosition.x();
        double cameraY = cameraPosition.y();
        double cameraZ = cameraPosition.z();
        TickRateManager tickRateManager = this.client.level.tickRateManager();
        double viewScale = Mth.clamp(this.client.options.getEffectiveRenderDistance() / 8.0, 1.0, 2.5)
                * this.client.options.entityDistanceScaling().get();
        Entity.setViewScale(viewScale);
        DeltaTracker deltaTracker = this.client.getDeltaTracker();
        EntityRenderDispatcher dispatcher = this.client.getEntityRenderDispatcher();

        for (Entity entity : this.client.level.entitiesForRendering()) {
            if (!shouldExtractGeneralEntity(entity instanceof AbstractClientPlayer player && player.isSpectator())) {
                continue;
            }
            if (!dispatcher.shouldRender(entity, frustum, cameraX, cameraY, cameraZ)
                    && !entity.hasIndirectPassenger(this.client.player)) {
                continue;
            }
            BlockPos blockPos = entity.blockPosition();
            if (!this.client.level.isOutsideBuildHeight(blockPos.getY())
                    && !this.client.levelRenderer.isSectionCompiledAndVisible(blockPos)) {
                continue;
            }
            if (entity.tickCount == 0) {
                entity.xOld = entity.getX();
                entity.yOld = entity.getY();
                entity.zOld = entity.getZ();
            }
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(
                    !tickRateManager.isEntityFrozen(entity)
            );
            this.levelRenderState.entityRenderStates.add(dispatcher.extractEntity(entity, partialTick));
        }
    }

    private void extractPlayer(final EntityRenderDispatcher dispatcher, final float tickDelta) {
        LocalPlayer player = this.client.player;
        if (player == null) {
            throw new IllegalStateException("Iris Metal player shadows require a local player");
        }
        if (shouldExtractPlayer(player.isSpectator(), player.isInvisible())) {
            this.levelRenderState.entityRenderStates.add(dispatcher.extractEntity(player, tickDelta));
        }
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            this.levelRenderState.entityRenderStates.add(dispatcher.extractEntity(vehicle, tickDelta));
        }
    }

    private void submitBlockEntities(final Camera camera, final PoseStack shadowPose) {
        Vec3 cameraPosition = camera.position();
        BlockEntityRenderDispatcher dispatcher = this.client.getBlockEntityRenderDispatcher();
        for (BlockEntityRenderState state : this.levelRenderState.blockEntityRenderStates) {
            BlockPos blockPos = state.blockPos;
            shadowPose.pushPose();
            shadowPose.translate(
                    blockPos.getX() - cameraPosition.x,
                    blockPos.getY() - cameraPosition.y,
                    blockPos.getZ() - cameraPosition.z
            );
            dispatcher.submit(
                    state,
                    shadowPose,
                    this.submitNodeStorage,
                    this.levelRenderState.cameraRenderState
            );
            shadowPose.popPose();
        }
    }

    static boolean shouldExtractGeneralEntity(final boolean spectatorClientPlayer) {
        return !spectatorClientPlayer;
    }

    static boolean shouldExtractPlayer(final boolean spectator, final boolean invisible) {
        return !spectator && !invisible;
    }

    static boolean shouldRenderLightBlockEntity(final int lightEmission) {
        return lightEmission != 0;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.featureRenderDispatcher.close();
        this.renderBuffers.close();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris shadow feature submitter is closed");
        }
    }
}
