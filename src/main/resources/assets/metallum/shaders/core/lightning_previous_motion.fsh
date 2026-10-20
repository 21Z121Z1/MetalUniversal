#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:fog.glsl>
#include <metallum:motion_fragment.glsl>

layout(location = 2) in float metallumVertexAlpha;
layout(location = 3) in vec2 metallumFogDistances;
layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
#ifdef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    float alpha = metallumVertexAlpha * ColorModulator.a * (1.0 - total_fog_value(
            metallumFogDistances.x, metallumFogDistances.y, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd));
#ifdef METALLUM_ALPHA_COMPOSITE
    if (!(alpha > 0.0)) discard;
#endif
#ifndef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    metallumMotionTarget = metallumSample.motion;
    metallumValidityTarget = metallumExportValidity(metallumSample);
}
