from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one anchor in {path!r}, found {count}")
    p.write_text(text.replace(old, new, 1))


def create_once(path: str, content: str) -> None:
    p = Path(path)
    if p.exists():
        raise SystemExit(f"refusing to overwrite existing {p}")
    p.write_text(content)


replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalFxMotionEligibility.java",
    '''     * <p>Living entities change ModelPart vertices through setupAnim and boats animate child\n     * geometry (paddles), so they remain fail-closed until previous local vertices are supplied.</p>\n''',
    '''     * <p>Living entities still remain fail-closed. Boat paddles also deform in setupAnim, but\n     * Boat model submits are now admitted only as exact staged-previous-position objects; their\n     * water-mask depth patch has a separately verified POSITION-only exact ABI.</p>\n'''
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalFxMotionEligibility.java",
    '''        if (state instanceof LivingEntityRenderState || state instanceof BoatRenderState) {\n            return NON_RIGID_ENTITY;\n        }\n        if (state instanceof ItemEntityRenderState\n''',
    '''        if (state instanceof LivingEntityRenderState) {\n            return NON_RIGID_ENTITY;\n        }\n        if (state instanceof BoatRenderState) {\n            // Candidate only. MetalFxManager marks the whole object exact-required, so paddle\n            // deformation and the optional water-mask draw must both match staged history.\n            return 0;\n        }\n        if (state instanceof ItemEntityRenderState\n'''
)

replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalFxManager.java",
    '''        if (state instanceof net.minecraft.client.renderer.entity.state.DisplayEntityRenderState displayState\n                && MetalDisplayMotionSafety.requiresExactPreviousPositions(displayState)) {\n            // TextDisplay submits both TextFeature and CustomGeometry (background) draws. Class-level\n            // admission is therefore only a candidate; the whole per-object staged manifest must\n            // match and every draw must actually encode exact previous positions before interpolation.\n            MetalEntityMotionCapture.requireExactState(state);\n        }\n''',
    '''        boolean requiresExactPreviousPositions =\n                state instanceof net.minecraft.client.renderer.entity.state.BoatRenderState\n                || (state instanceof net.minecraft.client.renderer.entity.state.DisplayEntityRenderState displayState\n                && MetalDisplayMotionSafety.requiresExactPreviousPositions(displayState));\n        if (requiresExactPreviousPositions) {\n            // TextDisplay background/text and Boat model/water-mask geometry are candidate families\n            // only. The whole per-object staged manifest must match the previous successfully\n            // submitted source frame and every draw must actually encode exact previous positions.\n            MetalEntityMotionCapture.requireExactState(state);\n        }\n'''
)

replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalEntityMotionPipeline.java",
    '''        TEXT("core/text_previous_motion", "core/text_previous_motion", "text_previous_motion/"),\n        TEXT_BACKGROUND("core/text_background_previous_motion", "core/text_background_previous_motion", "text_background_previous_motion/");\n''',
    '''        TEXT("core/text_previous_motion", "core/text_previous_motion", "text_previous_motion/"),\n        TEXT_BACKGROUND("core/text_background_previous_motion", "core/text_background_previous_motion", "text_background_previous_motion/"),\n        WATER_MASK("core/position_previous_motion", "core/position_previous_motion", "position_previous_motion/");\n'''
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/MetalEntityMotionPipeline.java",
    '''        if (shader.equals("core/text_background")) {\n            boolean seeThrough = source.getShaderDefines().flags().contains("IS_SEE_THROUGH");\n            VertexFormat expected = seeThrough\n                    ? DefaultVertexFormat.POSITION_COLOR\n                    : DefaultVertexFormat.POSITION_COLOR_LIGHTMAP;\n            return expected.equals(format) ? PreviousFamily.TEXT_BACKGROUND : null;\n        }\n        return null;\n''',
    '''        if (shader.equals("core/text_background")) {\n            boolean seeThrough = source.getShaderDefines().flags().contains("IS_SEE_THROUGH");\n            VertexFormat expected = seeThrough\n                    ? DefaultVertexFormat.POSITION_COLOR\n                    : DefaultVertexFormat.POSITION_COLOR_LIGHTMAP;\n            return expected.equals(format) ? PreviousFamily.TEXT_BACKGROUND : null;\n        }\n        if (shader.equals("core/rendertype_water_mask")\n                && DefaultVertexFormat.POSITION.equals(format)) {\n            return PreviousFamily.WATER_MASK;\n        }\n        return null;\n'''
)

create_once(
    "src/main/resources/assets/metallum/shaders/core/position_previous_motion.vsh",
    '''#version 330\n\n#moj_import <minecraft:dynamictransforms.glsl>\n#moj_import <minecraft:projection.glsl>\n\n// Minecraft 26.2 water_mask has POSITION as its sole binding-0 attribute, so\n// the compact previous-position stream starts at location 1.\nlayout(location = 0) in vec3 Position;\nlayout(location = 1) in vec3 PreviousPosition;\n\nlayout(std140) uniform MetallumMotion {\n    mat4 CurrentUnjitteredFromRaster;\n    mat4 PreviousFromRaster;\n};\n\nnoperspective out vec2 metallumObjectMotion;\nflat out float metallumObjectValidity;\n\nvoid main() {\n    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);\n    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;\n    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);\n    gl_Position = rasterClip;\n\n    bool valid = currentClip.w > 1.0e-6 && previousClip.w > 1.0e-6;\n    if (valid) {\n        vec2 currentNdc = currentClip.xy / currentClip.w;\n        vec2 previousNdc = previousClip.xy / previousClip.w;\n        vec2 motion = vec2(\n            previousNdc.x - currentNdc.x,\n            currentNdc.y - previousNdc.y\n        );\n        valid = !any(isnan(currentNdc)) && !any(isinf(currentNdc))\n            && !any(isnan(previousNdc)) && !any(isinf(previousNdc))\n            && !any(isnan(motion)) && !any(isinf(motion))\n            && all(lessThanEqual(abs(motion), vec2(32.0)));\n        metallumObjectMotion = valid ? motion : vec2(0.0);\n    } else {\n        metallumObjectMotion = vec2(0.0);\n    }\n    metallumObjectValidity = valid ? 1.0 : 0.0;\n}\n'''
)
create_once(
    "src/main/resources/assets/metallum/shaders/core/position_previous_motion.fsh",
    '''#version 330\n\nnoperspective in vec2 metallumObjectMotion;\nflat in float metallumObjectValidity;\n\nlayout(location = 0) out vec2 metallumMotionTarget;\nlayout(location = 1) out float metallumValidityTarget;\n\nvoid main() {\n    // water_mask is depth/mask geometry with no sampled alpha in its POSITION ABI.\n    // Its source color target writes no color channels, but its depth still participates\n    // in the frame-interpolator source contract, so encode exact geometry motion here.\n    metallumMotionTarget = metallumObjectMotion;\n    metallumValidityTarget = metallumObjectValidity;\n}\n'''
)

replace_once(
    "src/test/java/com/metallum/client/metal/render/MetalFxMotionEligibilityTest.java",
    '''import net.minecraft.client.renderer.entity.state.ArrowRenderState;\n''',
    '''import net.minecraft.client.renderer.entity.state.ArrowRenderState;\nimport net.minecraft.client.renderer.entity.state.BoatRenderState;\n'''
)
replace_once(
    "src/test/java/com/metallum/client/metal/render/MetalFxMotionEligibilityTest.java",
    '''    @Test\n    void nonRigidAndUnknownFamiliesFailClosed() {\n''',
    '''    @Test\n    void boatIsAnExactStagedGeometryCandidate() {\n        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new BoatRenderState()));\n    }\n\n    @Test\n    void nonRigidAndUnknownFamiliesFailClosed() {\n'''
)

replace_once(
    "src/test/java/com/metallum/client/metal/render/MetalEntityAuxiliaryMotionPipelineTest.java",
    '''    @Test\n    void wrongLayoutsRemainFailClosed() {\n''',
    '''    @Test\n    void waterMaskUsesPositionOnlyExactPreviousAbi() {\n        RenderPipeline waterMask = pipeline("core/rendertype_water_mask", DefaultVertexFormat.POSITION);\n        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(waterMask));\n        assertTrue(MetalEntityMotionPipeline.isSplittableVertexShader(waterMask));\n        assertFalse(MetalEntityMotionPipeline.supports(waterMask));\n        RenderPipeline exact = MetalEntityMotionPipeline.forPreviousPositions(waterMask);\n        assertEquals("core/position_previous_motion", exact.getVertexShader().getPath());\n        assertEquals("core/position_previous_motion", exact.getFragmentShader().getPath());\n    }\n\n    @Test\n    void wrongLayoutsRemainFailClosed() {\n'''
)
replace_once(
    "src/test/java/com/metallum/client/metal/render/MetalEntityAuxiliaryMotionPipelineTest.java",
    '''        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(\n                pipeline("core/text_background", DefaultVertexFormat.POSITION_COLOR)));\n''',
    '''        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(\n                pipeline("core/text_background", DefaultVertexFormat.POSITION_COLOR)));\n        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(\n                pipeline("core/rendertype_water_mask", DefaultVertexFormat.ENTITY)));\n'''
)
