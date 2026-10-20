#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

#include <metallum:motion_fragment.glsl>
layout(location = 2) in vec4 metallumVertexColor;

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
    MetallumMotionSample metallumSample = metallumMotionSample();
    metallumMotionTarget = metallumSample.motion;
    metallumValidityTarget = metallumSample.validity;
}
