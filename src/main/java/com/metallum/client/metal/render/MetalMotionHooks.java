package com.metallum.client.metal.render;

/**
 * Bytecode signatures the motion hooks inject against.
 *
 * <p>These live in a constant so the mixin annotation and the test that checks the
 * signature still exists cannot drift apart. All of them are compile-time
 * constants, which is what lets an annotation reference them; the compiler inlines
 * the value into the class file, so Mixin sees a plain string.</p>
 *
 * <p>A signature that stops matching after a Minecraft update would otherwise
 * surface only when the mixin config loads — during client startup, long after the
 * build passed. {@code MetalMotionHookDescriptorTest} turns that into a build
 * failure that names the method whose shape changed.</p>
 */
public final class MetalMotionHooks {
    public static final String MODEL_BLOCK_RENDERER_CLASS = "net.minecraft.client.renderer.block.ModelBlockRenderer";
    public static final String MOVING_BLOCK_SUBMIT_CLASS =
            "net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer$Submit";

    public static final String BLOCK_ENTITY_RENDERER_CLASS =
            "net.minecraft.client.renderer.blockentity.BlockEntityRenderer";

    public static final String BLOCK_ENTITY_SUBMIT_DESCRIPTOR =
            "(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

    public static final String BLOCK_ENTITY_SUBMIT_TARGET =
            "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit"
                    + BLOCK_ENTITY_SUBMIT_DESCRIPTOR;

    public static final String ITEM_FRAME_RENDERER_CLASS =
            "net.minecraft.client.renderer.entity.ItemFrameRenderer";
    public static final String ITEM_FRAME_SUBMIT_DESCRIPTOR =
            "(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
    public static final String ITEM_FRAME_SUBMIT_NAME = "submit";

    public static final String BLOCK_MODEL_RENDER_STATE_CLASS =
            "net.minecraft.client.renderer.block.BlockModelRenderState";
    public static final String BLOCK_MODEL_SUBMIT_WITH_Z_OFFSET_DESCRIPTOR =
            "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V";
    public static final String BLOCK_MODEL_SUBMIT_WITH_Z_OFFSET_TARGET =
            "Lnet/minecraft/client/renderer/block/BlockModelRenderState;submitWithZOffset"
                    + BLOCK_MODEL_SUBMIT_WITH_Z_OFFSET_DESCRIPTOR;

    public static final String ITEM_STACK_RENDER_STATE_CLASS =
            "net.minecraft.client.renderer.item.ItemStackRenderState";
    public static final String ITEM_SUBMIT_DESCRIPTOR =
            "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V";
    public static final String ITEM_SUBMIT_TARGET =
            "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit"
                    + ITEM_SUBMIT_DESCRIPTOR;

    public static final String MAP_RENDERER_CLASS = "net.minecraft.client.renderer.MapRenderer";
    public static final String MAP_RENDER_DESCRIPTOR =
            "(Lnet/minecraft/client/renderer/state/MapRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;ZI)V";
    public static final String MAP_RENDER_TARGET =
            "Lnet/minecraft/client/renderer/MapRenderer;render" + MAP_RENDER_DESCRIPTOR;

    public static final String BLOCK_MODEL_FEATURE_SUBMIT_CLASS =
            "net.minecraft.client.renderer.feature.BlockModelFeatureRenderer$Submit";
    public static final String BLOCK_MODEL_FEATURE_SUBMIT_DESCRIPTOR =
            "(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
                    + "Lnet/minecraft/client/renderer/rendertype/RenderType;Ljava/util/List;[IIII"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)V";
    public static final String CUSTOM_FEATURE_SUBMIT_CLASS =
            "net.minecraft.client.renderer.feature.CustomFeatureRenderer$Submit";
    public static final String CUSTOM_FEATURE_SUBMIT_DESCRIPTOR =
            "(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
                    + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V";

    public static final String END_CRYSTAL_RENDERER_CLASS =
            "net.minecraft.client.renderer.entity.EndCrystalRenderer";
    public static final String END_CRYSTAL_SUBMIT_DESCRIPTOR =
            "(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
    public static final String END_CRYSTAL_SUBMIT_NAME = "submit";
    public static final String SUBMIT_NODE_COLLECTOR_CLASS =
            "net.minecraft.client.renderer.SubmitNodeCollector";
    public static final String SUBMIT_MODEL_DESCRIPTOR =
            "(Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/resources/Identifier;IIIL"
                    + "net/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V";
    public static final String SUBMIT_MODEL_TARGET =
            "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel"
                    + SUBMIT_MODEL_DESCRIPTOR;
    public static final String ENDER_DRAGON_RENDERER_CLASS =
            "net.minecraft.client.renderer.entity.EnderDragonRenderer";
    public static final String CRYSTAL_BEAMS_NAME = "submitCrystalBeams";
    public static final String CRYSTAL_BEAMS_DESCRIPTOR =
            "(FFFFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V";
    public static final String CRYSTAL_BEAMS_TARGET =
            "Lnet/minecraft/client/renderer/entity/EnderDragonRenderer;"
                    + CRYSTAL_BEAMS_NAME + CRYSTAL_BEAMS_DESCRIPTOR;

    public static final String RENDER_TYPE_FEATURE_RENDERER_CLASS =
            "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer";
    public static final String GET_VERTEX_BUILDER_NAME = "getVertexBuilder";
    public static final String GET_VERTEX_BUILDER_DESCRIPTOR =
            "(Lnet/minecraft/client/renderer/rendertype/RenderType;)"
                    + "Lcom/mojang/blaze3d/vertex/VertexConsumer;";
    public static final String GET_VERTEX_BUILDER_TARGET =
            "Lnet/minecraft/client/renderer/feature/RenderTypeFeatureRenderer;"
                    + GET_VERTEX_BUILDER_NAME + GET_VERTEX_BUILDER_DESCRIPTOR;

    public static final String BLOCK_MODEL_FEATURE_RENDERER_CLASS =
            "net.minecraft.client.renderer.feature.BlockModelFeatureRenderer";
    public static final String CUSTOM_FEATURE_RENDERER_CLASS =
            "net.minecraft.client.renderer.feature.CustomFeatureRenderer";
    public static final String BLOCK_MODEL_GET_VERTEX_BUILDER_TARGET =
            "Lnet/minecraft/client/renderer/feature/BlockModelFeatureRenderer;"
                    + GET_VERTEX_BUILDER_NAME + GET_VERTEX_BUILDER_DESCRIPTOR;
    public static final String CUSTOM_GET_VERTEX_BUILDER_TARGET =
            "Lnet/minecraft/client/renderer/feature/CustomFeatureRenderer;"
                    + GET_VERTEX_BUILDER_NAME + GET_VERTEX_BUILDER_DESCRIPTOR;
    public static final String BUILD_GROUP_DESCRIPTOR =
            "(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;)V";

    public static final String MOVING_BLOCK_FEATURE_RENDERER_CLASS =
            "net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer";

    /**
     * The method the moving-block wrapper is scoped to. Independent of the descriptor
     * below: a rename here leaves the descriptor valid and the injection unplaceable.
     */
    public static final String BUILD_GROUP_METHOD = "buildGroup";

    public static final String TESSELATE_BLOCK_NAME = "tesselateBlock";

    /**
     * {@code ModelBlockRenderer.tesselateBlock}. The fifth parameter is declared
     * {@code BlockAndTintGetter}, and at the moving-block call site the argument is
     * the submit's own {@code MovingBlockRenderState}, which is the key the motion
     * sample was recorded under.
     */
    public static final String TESSELATE_BLOCK_DESCRIPTOR =
            "(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFF"
                    + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                    + "Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V";

    /** Full Mixin {@code @At} target for the moving-block tesselation call. */
    public static final String TESSELATE_BLOCK_TARGET =
            "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;"
                    + TESSELATE_BLOCK_NAME + TESSELATE_BLOCK_DESCRIPTOR;

    /** {@code MovingBlockFeatureRenderer.Submit(Matrix4fc, MovingBlockRenderState, int)}. */
    public static final String MOVING_BLOCK_SUBMIT_DESCRIPTOR =
            "(Lorg/joml/Matrix4fc;"
                    + "Lnet/minecraft/client/renderer/block/MovingBlockRenderState;I)V";

    private MetalMotionHooks() {
    }
}
