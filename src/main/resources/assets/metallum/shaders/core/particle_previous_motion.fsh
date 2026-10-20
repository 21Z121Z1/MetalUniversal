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
    // Match vanilla particle.fsh visibility: texture * vertexColor * ColorModulator, alpha < 0.1
    // discarded. Lightmap alpha is 1 for the particle vertex-color path, so only RGB lighting is
    // intentionally irrelevant to the binary coverage decision here.
    float alpha = texture(Sampler0, metallumTexCoord).a * metallumVertexAlpha * ColorModulator.a;
    if (alpha < 0.1) {
        discard;
    }
    MetallumMotionSample metallumSample = metallumMotionSample();
    metallumMotionTarget = metallumSample.motion;
    metallumValidityTarget = metallumSample.validity;
}
