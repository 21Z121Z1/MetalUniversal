#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

noperspective in vec2 metallumObjectMotion;
flat in float metallumObjectValidity;
in vec2 metallumTexCoord;
flat in float metallumVertexAlpha;

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
    metallumMotionTarget = metallumObjectMotion;
    metallumValidityTarget = metallumObjectValidity;
}
