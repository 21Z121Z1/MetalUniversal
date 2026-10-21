#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;
#include <minecraft:dynamictransforms.glsl>

#include <metallum:motion_fragment.glsl>
layout(location = 2) in vec2 metallumTexCoord;
layout(location = 3) flat in float metallumVertexColorGuard;
layout(location = 4) in float metallumVertexAlpha;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
#ifdef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    // Keeps Color active in the reduced vertex shader so UV0 retains the block
    // format's attribute 2. Vertex color is normalized and therefore cannot
    // satisfy this guard; it has no coverage effect.
    if (metallumVertexColorGuard < -1.0) {
        discard;
    }
    // Unlike ENTITY, BLOCK cuts out after vertex/modulator alpha multiplication.
    float alpha = texture(Sampler0, metallumTexCoord).a * metallumVertexAlpha * ColorModulator.a;
#ifdef ALPHA_CUTOUT
    if (alpha < ALPHA_CUTOUT) {
        discard;
    }
#endif
#ifdef METALLUM_ALPHA_COMPOSITE
    if (alpha <= 0.0) discard;
#endif
#ifndef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    metallumMotionTarget = metallumSample.motion;
    metallumValidityTarget = metallumExportValidity(metallumSample);
}
