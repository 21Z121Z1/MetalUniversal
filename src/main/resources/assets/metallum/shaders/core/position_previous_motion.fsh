#version 330

noperspective in vec2 metallumObjectMotion;
flat in float metallumObjectValidity;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
    // water_mask is depth/mask geometry with no sampled alpha in its POSITION ABI.
    // Its source color target writes no color channels, but its depth still participates
    // in the frame-interpolator source contract, so encode exact geometry motion here.
    metallumMotionTarget = metallumObjectMotion;
    metallumValidityTarget = metallumObjectValidity;
}
