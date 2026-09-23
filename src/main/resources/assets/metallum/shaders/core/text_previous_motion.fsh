#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;

layout(location = 0) noperspective in vec2 metallumObjectMotion;
layout(location = 1) flat in float metallumObjectValidity;
layout(location = 2) in vec4 metallumVertexColor;
layout(location = 3) in vec2 metallumTexCoord;

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
