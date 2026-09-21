#version 330

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
