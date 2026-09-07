#version 330

uniform sampler2D Sampler0;

noperspective in vec2 metallumObjectMotion;
flat in float metallumObjectValidity;
in vec2 metallumTexCoord;
flat in float metallumVertexColorGuard;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
    // Minecraft's entity shadow is a translucent textured quad. Pixels with exactly zero sampled
    // coverage do not contribute to the source color, so they must not overwrite scene motion.
    // Semitransparent texels do contribute and receive the same exact geometric motion.
    float coverage = texture(Sampler0, metallumTexCoord).a * metallumVertexColorGuard;
    if (!(coverage > 0.0)) {
        discard;
    }
    metallumMotionTarget = metallumObjectMotion;
    metallumValidityTarget = metallumObjectValidity;
}
