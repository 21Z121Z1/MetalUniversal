#version 330

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
