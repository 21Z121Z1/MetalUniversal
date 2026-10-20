#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

#include <metallum:motion_fragment.glsl>
layout(location = 2) in vec2 metallumTexCoord;
layout(location = 3) in float metallumVertexAlpha;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
#ifdef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    // Match vanilla particle.fsh visibility: texture * vertexColor * ColorModulator, alpha < 0.1
    // discarded. The vertex alpha includes the actual sampled lightmap alpha;
    // its normal vanilla value of 1 is not a visibility contract for replacements.
    float alpha = texture(Sampler0, metallumTexCoord).a * metallumVertexAlpha * ColorModulator.a;
    if (alpha < 0.1) {
        discard;
    }
#ifndef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    metallumMotionTarget = metallumSample.motion;
    metallumValidityTarget = metallumExportValidity(metallumSample);
}
