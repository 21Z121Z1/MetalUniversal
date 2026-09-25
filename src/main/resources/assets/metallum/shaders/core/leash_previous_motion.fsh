#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) noperspective in vec2 metallumObjectMotion;
layout(location = 1) flat in float metallumObjectValidity;
layout(location = 2) flat in float metallumAttributeGuard;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
    if (metallumAttributeGuard < -1.0) {
        discard;
    }
    metallumMotionTarget = metallumObjectMotion;
    metallumValidityTarget = metallumObjectValidity;
}
