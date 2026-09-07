#version 330

noperspective in vec2 metallumObjectMotion;
flat in float metallumObjectValidity;
flat in float metallumAttributeGuard;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
    if (metallumAttributeGuard < -1.0) {
        discard;
    }
    metallumMotionTarget = metallumObjectMotion;
    metallumValidityTarget = metallumObjectValidity;
}
