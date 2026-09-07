from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


pipeline = "src/main/java/com/metallum/client/metal/render/MetalEntityMotionPipeline.java"
replace_once(
    pipeline,
    '''    private static final Identifier ENTITY_PREVIOUS_VERTEX_SHADER =\n            Identifier.fromNamespaceAndPath("metallum", "core/entity_previous_motion");\n''',
    '''    private enum PreviousFamily {\n        ENTITY("core/entity_previous_motion", "core/entity_motion", "entity_previous_motion/"),\n        LEASH("core/leash_previous_motion", "core/leash_previous_motion", "leash_previous_motion/"),\n        TEXT("core/text_previous_motion", "core/text_previous_motion", "text_previous_motion/"),\n        TEXT_BACKGROUND("core/text_background_previous_motion", "core/text_background_previous_motion", "text_background_previous_motion/");\n\n        private final Identifier vertexShader;\n        private final Identifier fragmentShader;\n        private final String locationPrefix;\n\n        PreviousFamily(final String vertexPath, final String fragmentPath, final String locationPrefix) {\n            this.vertexShader = Identifier.fromNamespaceAndPath("metallum", vertexPath);\n            this.fragmentShader = Identifier.fromNamespaceAndPath("metallum", fragmentPath);\n            this.locationPrefix = locationPrefix;\n        }\n    }\n'''
)
replace_once(
    pipeline,
    '''    static boolean isSplittableVertexShader(final RenderPipeline source) {\n        return familyOf(source) != null;\n    }\n\n    static boolean supports(final RenderPipeline source) {\n        if (!isSplittableVertexShader(source)) {\n            return false;\n        }\n''',
    '''    static boolean isSplittableVertexShader(final RenderPipeline source) {\n        return familyOf(source) != null || previousFamilyOf(source) != null;\n    }\n\n    static boolean supports(final RenderPipeline source) {\n        if (familyOf(source) == null) {\n            return false;\n        }\n'''
)
replace_once(
    pipeline,
    '''    /**\n     * True only for the vanilla ENTITY ABI whose CPU-staged Position contains the exact\n     * entity/model PoseStack result. The second compact stream is deliberately not attached to\n     * BLOCK or an unknown/custom vertex ABI; those keep the proven root-transform replay.\n     */\n    static boolean supportsPreviousPositions(final RenderPipeline source) {\n        if (familyOf(source) != Family.ENTITY || !supports(source)) {\n            return false;\n        }\n        VertexFormat[] bindings = source.getVertexFormatBindings();\n        return bindings.length > 0\n                && DefaultVertexFormat.ENTITY.equals(bindings[0])\n                && (bindings.length < 2 || bindings[1] == null);\n    }\n''',
    '''    /**\n     * Exact staged-position replay ABIs proven against the Minecraft 26.2 client shaders.\n     * Root-transform support is intentionally independent: leash and world text are exact-only\n     * families and must never fall back to a closest-looking root motion shader.\n     */\n    static boolean supportsPreviousPositions(final RenderPipeline source) {\n        return previousFamilyOf(source) != null;\n    }\n\n    private static @Nullable PreviousFamily previousFamilyOf(final RenderPipeline source) {\n        if (source == null) {\n            return null;\n        }\n        VertexFormat[] bindings = source.getVertexFormatBindings();\n        if (bindings.length == 0 || bindings[0] == null || (bindings.length >= 2 && bindings[1] != null)) {\n            return null;\n        }\n        VertexFormat format = bindings[0];\n        String shader = source.getVertexShader().getPath();\n        if ((shader.equals("core/entity") || shader.equals("core/item"))\n                && DefaultVertexFormat.ENTITY.equals(format)\n                && supports(source)) {\n            return PreviousFamily.ENTITY;\n        }\n        if (shader.equals("core/rendertype_leash")\n                && DefaultVertexFormat.POSITION_COLOR_LIGHTMAP.equals(format)) {\n            return PreviousFamily.LEASH;\n        }\n        if (shader.equals("core/text") && !source.getShaderDefines().flags().contains("IS_GUI")) {\n            boolean seeThrough = source.getShaderDefines().flags().contains("IS_SEE_THROUGH");\n            VertexFormat expected = seeThrough\n                    ? DefaultVertexFormat.POSITION_TEX_COLOR\n                    : DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR;\n            return expected.equals(format) ? PreviousFamily.TEXT : null;\n        }\n        if (shader.equals("core/text_background")) {\n            boolean seeThrough = source.getShaderDefines().flags().contains("IS_SEE_THROUGH");\n            VertexFormat expected = seeThrough\n                    ? DefaultVertexFormat.POSITION_COLOR\n                    : DefaultVertexFormat.POSITION_COLOR_LIGHTMAP;\n            return expected.equals(format) ? PreviousFamily.TEXT_BACKGROUND : null;\n        }\n        return null;\n    }\n'''
)
replace_once(
    pipeline,
    '''    private static RenderPipeline buildPreviousPositions(final RenderPipeline source) {\n        return buildVariant(source, true);\n    }\n\n    private static RenderPipeline buildVariant(final RenderPipeline source, final boolean previousPositions) {\n        Family family = familyOf(source);\n        if (family == null) {\n            throw new IllegalArgumentException(\n                    "No motion family replays " + source.getLocation() + " (" + source.getVertexShader() + ")");\n        }\n        if (previousPositions && family != Family.ENTITY) {\n            throw new IllegalArgumentException("Previous-position replay is not defined for " + family);\n        }\n        String sourceName = source.getLocation().toString()\n''',
    '''    private static RenderPipeline buildPreviousPositions(final RenderPipeline source) {\n        PreviousFamily previousFamily = previousFamilyOf(source);\n        if (previousFamily == null) {\n            throw new IllegalArgumentException(\n                    "Source pipeline has no exact previous-position family: " + source.getLocation());\n        }\n        return buildVariant(source, null, previousFamily);\n    }\n\n    private static RenderPipeline buildVariant(final RenderPipeline source, final boolean previousPositions) {\n        Family family = familyOf(source);\n        if (family == null) {\n            throw new IllegalArgumentException(\n                    "No root motion family replays " + source.getLocation() + " (" + source.getVertexShader() + ")");\n        }\n        if (previousPositions) {\n            PreviousFamily previousFamily = previousFamilyOf(source);\n            if (previousFamily == null) {\n                throw new IllegalArgumentException("Previous-position replay is not defined for " + source.getLocation());\n            }\n            return buildVariant(source, family, previousFamily);\n        }\n        return buildVariant(source, family, null);\n    }\n\n    private static RenderPipeline buildVariant(\n            final RenderPipeline source,\n            final @Nullable Family family,\n            final @Nullable PreviousFamily previousFamily\n    ) {\n        boolean previousPositions = previousFamily != null;\n        if (!previousPositions && family == null) {\n            throw new IllegalArgumentException("Missing root motion family for " + source.getLocation());\n        }\n        String sourceName = source.getLocation().toString()\n'''
)
replace_once(
    pipeline,
    '''                        (previousPositions ? "entity_previous_motion/" : family.locationPrefix()) + sourceName\n                ))\n                .withVertexShader(previousPositions ? ENTITY_PREVIOUS_VERTEX_SHADER : family.shader())\n                .withFragmentShader(family.shader())\n''',
    '''                        (previousPositions ? previousFamily.locationPrefix : family.locationPrefix()) + sourceName\n                ))\n                .withVertexShader(previousPositions ? previousFamily.vertexShader : family.shader())\n                .withFragmentShader(previousPositions ? previousFamily.fragmentShader : family.shader())\n'''
)

capture = "src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java"
replace_once(
    capture,
    '''        lastVertexShader = pipeline.getVertexShader().toString();\n        boolean matched = MetalEntityMotionPipeline.isSplittableVertexShader(pipeline);\n        if (MetalExactMotionCoverage.required(sample)\n                && !MetalEntityMotionPipeline.supportsPreviousPositions(pipeline)) {\n''',
    '''        lastVertexShader = pipeline.getVertexShader().toString();\n        boolean rootSupported = MetalEntityMotionPipeline.supports(pipeline);\n        boolean exactSupported = MetalEntityMotionPipeline.supportsPreviousPositions(pipeline);\n        boolean matched = MetalEntityMotionPipeline.isSplittableVertexShader(pipeline);\n        if (exactSupported && !rootSupported) {\n            // Exact-only auxiliary families (leash/world text) must prove a complete previous\n            // staged manifest even for otherwise rigid entity classes. They have no safe root fallback.\n            MetalExactMotionCoverage.require(sample);\n        }\n        if (MetalExactMotionCoverage.required(sample) && !exactSupported) {\n'''
)

manager = "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
replace_once(
    manager,
    '''        if (!MetalEntityMotionPipeline.supports(prepared.pipeline())) {\n            MetalEntityMotionCapture.recordMotionDrawSkip("pipeline-unsupported");\n            return;\n        }\n\n        // Exact previous-position selection is intentionally deferred until flush. At that point all\n''',
    '''        boolean rootMotionSupported = MetalEntityMotionPipeline.supports(prepared.pipeline());\n        boolean exactMotionSupported = MetalEntityMotionPipeline.supportsPreviousPositions(prepared.pipeline());\n        if (!rootMotionSupported && !exactMotionSupported) {\n            MetalEntityMotionCapture.recordMotionDrawSkip("pipeline-unsupported");\n            return;\n        }\n\n        // Exact previous-position selection is intentionally deferred until flush. At that point all\n'''
)
replace_once(
    manager,
    '''            MetalPreviousVertexReplay.Plan exactPlan = MetalFxMath.isFinite(previousCameraRelativeViewProjection)\n                    ? MetalPreviousVertexReplay.plan(\n                            prepared.pipeline(), executeInfo, replay.previousVertexToken())\n                    : null;\n            boolean exactPreviousPositions = exactPlan != null;\n            Matrix4f previousFromRaster = exactPreviousPositions\n''',
    '''            boolean rootMotionSupported = MetalEntityMotionPipeline.supports(prepared.pipeline());\n            boolean exactMotionSupported = MetalEntityMotionPipeline.supportsPreviousPositions(prepared.pipeline());\n            MetalPreviousVertexReplay.Plan exactPlan = exactMotionSupported\n                    && MetalFxMath.isFinite(previousCameraRelativeViewProjection)\n                    ? MetalPreviousVertexReplay.plan(\n                            prepared.pipeline(), executeInfo, replay.previousVertexToken())\n                    : null;\n            boolean exactPreviousPositions = exactPlan != null;\n            if (!exactPreviousPositions && !rootMotionSupported) {\n                MetalEntityMotionCapture.recordMotionDrawSkip("exact-plan-unavailable");\n                continue;\n            }\n            Matrix4f previousFromRaster = exactPreviousPositions\n'''
)

Path("src/main/resources/assets/metallum/shaders/core/leash_previous_motion.vsh").write_text(r'''#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in ivec2 UV2;
layout(location = 3) in vec3 PreviousPosition;

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

noperspective out vec2 metallumObjectMotion;
flat out float metallumObjectValidity;
flat out float metallumAttributeGuard;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;

    bool valid = currentClip.w > 1.0e-6 && previousClip.w > 1.0e-6;
    if (valid) {
        vec2 currentNdc = currentClip.xy / currentClip.w;
        vec2 previousNdc = previousClip.xy / previousClip.w;
        vec2 motion = vec2(previousNdc.x - currentNdc.x, currentNdc.y - previousNdc.y);
        valid = !any(isnan(currentNdc)) && !any(isinf(currentNdc))
            && !any(isnan(previousNdc)) && !any(isinf(previousNdc))
            && !any(isnan(motion)) && !any(isinf(motion))
            && all(lessThanEqual(abs(motion), vec2(32.0)));
        metallumObjectMotion = valid ? motion : vec2(0.0);
    } else {
        metallumObjectMotion = vec2(0.0);
    }
    metallumObjectValidity = valid ? 1.0 : 0.0;
    // Keep all source binding-0 attributes live so PreviousPosition remains location 3.
    metallumAttributeGuard = Color.a + float(UV2.x + UV2.y) * 0.0;
}
''')
Path("src/main/resources/assets/metallum/shaders/core/leash_previous_motion.fsh").write_text(r'''#version 330

noperspective in vec2 metallumObjectMotion;
flat in float metallumObjectValidity;
flat in float metallumAttributeGuard;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
    if (metallumAttributeGuard < -1.0) {
        discard;
    }
    metallumMotionTarget = metallumObjectMotion;
    metallumValidityTarget = metallumObjectValidity;
}
''')

Path("src/main/resources/assets/metallum/shaders/core/text_previous_motion.vsh").write_text(r'''#version 330

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:sample_lightmap.glsl>
#endif
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
#ifdef IS_SEE_THROUGH
layout(location = 2) in vec4 Color;
layout(location = 3) in vec3 PreviousPosition;
#else
layout(location = 2) in ivec2 UV2;
layout(location = 3) in vec4 Color;
layout(location = 4) in vec3 PreviousPosition;
uniform sampler2D Sampler2;
#endif

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

noperspective out vec2 metallumObjectMotion;
flat out float metallumObjectValidity;
out vec4 metallumVertexColor;
out vec2 metallumTexCoord;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;

    bool valid = currentClip.w > 1.0e-6 && previousClip.w > 1.0e-6;
    if (valid) {
        vec2 currentNdc = currentClip.xy / currentClip.w;
        vec2 previousNdc = previousClip.xy / previousClip.w;
        vec2 motion = vec2(previousNdc.x - currentNdc.x, currentNdc.y - previousNdc.y);
        valid = !any(isnan(currentNdc)) && !any(isinf(currentNdc))
            && !any(isnan(previousNdc)) && !any(isinf(previousNdc))
            && !any(isnan(motion)) && !any(isinf(motion))
            && all(lessThanEqual(abs(motion), vec2(32.0)));
        metallumObjectMotion = valid ? motion : vec2(0.0);
    } else {
        metallumObjectMotion = vec2(0.0);
    }
    metallumObjectValidity = valid ? 1.0 : 0.0;
#ifdef IS_SEE_THROUGH
    metallumVertexColor = Color;
#else
    metallumVertexColor = Color * sample_lightmap(Sampler2, UV2);
#endif
    metallumTexCoord = UV0;
}
''')
Path("src/main/resources/assets/metallum/shaders/core/text_previous_motion.fsh").write_text(r'''#version 330

#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;

noperspective in vec2 metallumObjectMotion;
flat in float metallumObjectValidity;
in vec4 metallumVertexColor;
in vec2 metallumTexCoord;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, metallumTexCoord).rrrr;
#else
    vec4 texColor = texture(Sampler0, metallumTexCoord);
#endif
#ifdef IS_SEE_THROUGH
    vec4 color = texColor * metallumVertexColor;
#else
    vec4 color = texColor * metallumVertexColor * ColorModulator;
#endif
    if (color.a < 0.1) {
        discard;
    }
    metallumMotionTarget = metallumObjectMotion;
    metallumValidityTarget = metallumObjectValidity;
}
''')

Path("src/main/resources/assets/metallum/shaders/core/text_background_previous_motion.vsh").write_text(r'''#version 330

#ifndef IS_SEE_THROUGH
#moj_import <minecraft:sample_lightmap.glsl>
#endif
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
#ifdef IS_SEE_THROUGH
layout(location = 2) in vec3 PreviousPosition;
#else
layout(location = 2) in ivec2 UV2;
layout(location = 3) in vec3 PreviousPosition;
uniform sampler2D Sampler2;
#endif

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

noperspective out vec2 metallumObjectMotion;
flat out float metallumObjectValidity;
out vec4 metallumVertexColor;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;

    bool valid = currentClip.w > 1.0e-6 && previousClip.w > 1.0e-6;
    if (valid) {
        vec2 currentNdc = currentClip.xy / currentClip.w;
        vec2 previousNdc = previousClip.xy / previousClip.w;
        vec2 motion = vec2(previousNdc.x - currentNdc.x, currentNdc.y - previousNdc.y);
        valid = !any(isnan(currentNdc)) && !any(isinf(currentNdc))
            && !any(isnan(previousNdc)) && !any(isinf(previousNdc))
            && !any(isnan(motion)) && !any(isinf(motion))
            && all(lessThanEqual(abs(motion), vec2(32.0)));
        metallumObjectMotion = valid ? motion : vec2(0.0);
    } else {
        metallumObjectMotion = vec2(0.0);
    }
    metallumObjectValidity = valid ? 1.0 : 0.0;
#ifdef IS_SEE_THROUGH
    metallumVertexColor = Color;
#else
    metallumVertexColor = Color * sample_lightmap(Sampler2, UV2);
#endif
}
''')
Path("src/main/resources/assets/metallum/shaders/core/text_background_previous_motion.fsh").write_text(r'''#version 330

#moj_import <minecraft:dynamictransforms.glsl>

noperspective in vec2 metallumObjectMotion;
flat in float metallumObjectValidity;
in vec4 metallumVertexColor;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
#ifdef IS_SEE_THROUGH
    vec4 color = metallumVertexColor;
#else
    vec4 color = metallumVertexColor * ColorModulator;
#endif
    if (color.a < 0.1) {
        discard;
    }
    metallumMotionTarget = metallumObjectMotion;
    metallumValidityTarget = metallumObjectValidity;
}
''')

Path("src/test/java/com/metallum/client/metal/render/MetalEntityAuxiliaryMotionPipelineTest.java").write_text(r'''package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalEntityAuxiliaryMotionPipelineTest {
    @AfterEach
    void clear() {
        MetalEntityMotionPipeline.clear();
    }

    @Test
    void leashIsExactOnlyWithVerifiedMinecraftAbi() {
        RenderPipeline leash = pipeline("core/rendertype_leash", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP);
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(leash));
        assertTrue(MetalEntityMotionPipeline.isSplittableVertexShader(leash));
        assertFalse(MetalEntityMotionPipeline.supports(leash));
        assertEquals("core/leash_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(leash).getVertexShader().getPath());
    }

    @Test
    void worldAndSeeThroughTextUseTheirExactLayouts() {
        RenderPipeline world = pipeline("core/text", DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR);
        RenderPipeline seeThrough = pipeline("core/text", DefaultVertexFormat.POSITION_TEX_COLOR, "IS_SEE_THROUGH");
        RenderPipeline gui = pipeline("core/text", DefaultVertexFormat.POSITION_TEX_COLOR, "IS_GUI");
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(world));
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(seeThrough));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(gui));
        assertEquals("core/text_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(world).getVertexShader().getPath());
        assertEquals("core/text_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(seeThrough).getVertexShader().getPath());
    }

    @Test
    void textBackgroundNormalAndSeeThroughUseDifferentVerifiedLayouts() {
        RenderPipeline world = pipeline("core/text_background", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP);
        RenderPipeline seeThrough = pipeline("core/text_background", DefaultVertexFormat.POSITION_COLOR, "IS_SEE_THROUGH");
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(world));
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(seeThrough));
        assertEquals("core/text_background_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(world).getVertexShader().getPath());
        assertEquals("core/text_background_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(seeThrough).getVertexShader().getPath());
    }

    @Test
    void wrongLayoutsRemainFailClosed() {
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(
                pipeline("core/rendertype_leash", DefaultVertexFormat.ENTITY)));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(
                pipeline("core/text", DefaultVertexFormat.POSITION_TEX_COLOR)));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(
                pipeline("core/text_background", DefaultVertexFormat.POSITION_COLOR)));
    }

    private static RenderPipeline pipeline(final String shader, final VertexFormat format, final String... defines) {
        Identifier shaderId = Identifier.fromNamespaceAndPath("minecraft", shader);
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metallum", "test/" + shader.replace('/', '_') + "/" + format.getVertexSize()))
                .withVertexShader(shaderId)
                .withFragmentShader(shaderId)
                .withVertexBinding(0, format);
        for (String define : defines) {
            builder.withShaderDefine(define);
        }
        return builder.build();
    }
}
''')
