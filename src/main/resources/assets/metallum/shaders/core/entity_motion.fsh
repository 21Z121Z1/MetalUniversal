#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;

layout(location = 0) noperspective in vec2 metallumObjectMotion;
layout(location = 1) flat in float metallumObjectValidity;
layout(location = 2) in vec2 metallumTexCoord;
layout(location = 3) flat in float metallumVertexColorGuard;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
    // Keeps Color active in the reduced vertex shader so UV0 retains the
    // entity format's attribute 2. Vertex color is normalized and therefore
    // cannot satisfy this guard; it has no coverage effect.
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
