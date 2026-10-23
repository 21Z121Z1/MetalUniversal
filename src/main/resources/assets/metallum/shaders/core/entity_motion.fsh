#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;
#if defined(DISSOLVE) && !defined(METALLUM_ITEM_SOURCE)
uniform sampler2D DissolveMaskSampler;
#endif
#ifdef GLINT
#include <minecraft:globals.glsl>
#endif
#include <minecraft:dynamictransforms.glsl>

#include <metallum:motion_fragment.glsl>
layout(location = 2) in vec2 metallumTexCoord;
layout(location = 3) flat in float metallumVertexColorGuard;
layout(location = 4) in float metallumVertexAlpha;
#ifdef METALLUM_ENTITY_LIGHTMAP
layout(location = 6) in float metallumLightmapAlpha;
#endif

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
#ifdef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    // Keeps Color active in the reduced vertex shader so UV0 retains the
    // entity format's attribute 2. Vertex color is normalized and therefore
    // cannot satisfy this guard; it has no coverage effect.
    if (metallumVertexColorGuard < -1.0) {
        discard;
    }
    float alpha = texture(Sampler0, metallumTexCoord).a;
#if defined(OIT_ADDITIVE) && !defined(METALLUM_ITEM_SOURCE)
    alpha = min(0.99, alpha);
#endif
#ifdef ALPHA_CUTOUT
    if (alpha < ALPHA_CUTOUT) {
        discard;
    }
#endif
    float vertexAlpha = metallumVertexAlpha;
#if defined(DISSOLVE) && !defined(METALLUM_ITEM_SOURCE)
    if (vertexAlpha < texture(DissolveMaskSampler, metallumTexCoord).a) discard;
    vertexAlpha = 1.0;
#endif
    alpha *= vertexAlpha * ColorModulator.a;
#ifdef GLINT
    alpha = max(alpha, GlintAlpha);
#endif
#ifdef METALLUM_ENTITY_LIGHTMAP
    // ENTITY/ITEM sample independently in VS, then multiply after cutout,
    // dissolve replacement and GLINT's alpha floor in calculateFinalColor.
    alpha *= metallumLightmapAlpha;
#endif
#ifdef METALLUM_ALPHA_COMPOSITE
    // Alpha-blended and non-additive OIT surfaces with zero contribution must not
    // occlude another producer's flow in the private motion depth buffer.
    if (alpha <= 0.0) discard;
#endif
#ifndef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    metallumMotionTarget = metallumSample.motion;
    metallumValidityTarget = metallumExportValidity(metallumSample);
}
