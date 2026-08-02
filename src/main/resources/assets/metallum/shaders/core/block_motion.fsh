#version 330

uniform sampler2D Sampler0;

noperspective in vec2 metallumObjectMotion;
flat in float metallumObjectValidity;
in vec2 metallumTexCoord;
flat in float metallumVertexColorGuard;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
    // Keeps Color active in the reduced vertex shader so UV0 retains the block
    // format's attribute 2. Vertex color is normalized and therefore cannot
    // satisfy this guard; it has no coverage effect.
    if (metallumVertexColorGuard < -1.0) {
        discard;
    }
#ifdef ALPHA_CUTOUT
    if (texture(Sampler0, metallumTexCoord).a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    metallumMotionTarget = metallumObjectMotion;
    metallumValidityTarget = metallumObjectValidity;
}
