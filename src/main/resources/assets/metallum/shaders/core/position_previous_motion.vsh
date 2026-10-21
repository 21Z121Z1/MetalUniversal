#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Minecraft 26.2 water_mask has POSITION as its sole binding-0 attribute, so
// the compact previous-position stream starts at location 1.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 PreviousPosition;
#ifdef METALLUM_PREVIOUS_VALIDITY
layout(location = 2) in float PreviousValidity;
#endif

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

#include <metallum:motion_vertex.glsl>

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;

    metallumSetClipHistory(currentClip, previousClip);
}
