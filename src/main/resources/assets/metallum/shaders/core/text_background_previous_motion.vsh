#version 330
#extension GL_ARB_separate_shader_objects : require

#ifndef IS_SEE_THROUGH
#include <minecraft:sample_lightmap.glsl>
#endif
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
#ifdef IS_SEE_THROUGH
layout(location = 2) in vec3 PreviousPosition;
#ifdef METALLUM_PREVIOUS_VALIDITY
layout(location = 3) in float PreviousValidity;
#endif
#else
layout(location = 2) in ivec2 UV2;
layout(location = 3) in vec3 PreviousPosition;
#ifdef METALLUM_PREVIOUS_VALIDITY
layout(location = 4) in float PreviousValidity;
#endif
uniform sampler2D Sampler2;
#endif

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

#include <metallum:motion_vertex.glsl>
layout(location = 2) out vec4 metallumVertexColor;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;

    metallumSetClipHistory(currentClip, previousClip);
#ifdef IS_SEE_THROUGH
    metallumVertexColor = Color;
#else
    metallumVertexColor = Color * sample_lightmap(Sampler2, UV2);
#endif
}
