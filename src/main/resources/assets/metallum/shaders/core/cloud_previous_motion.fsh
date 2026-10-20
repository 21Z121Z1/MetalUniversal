#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <metallum:motion_fragment.glsl>
layout(location = 2) in float metallumCloudAlpha;
layout(location = 3) in float metallumCloudDistance;
layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
#ifdef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample sample=metallumMotionSample();
#endif
    float alpha=metallumCloudAlpha*(1.0-linear_fog_value(metallumCloudDistance,0.0,FogCloudsEnd));
    if(alpha<=0.0)discard;
#ifndef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample sample=metallumMotionSample();
#endif
    metallumMotionTarget=sample.motion;
    metallumValidityTarget=metallumExportValidity(sample);
}
