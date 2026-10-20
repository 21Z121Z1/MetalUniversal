#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:sample_lightmap.glsl>
#endif
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
#ifdef IS_SEE_THROUGH
layout(location = 2) in vec4 Color;
layout(location = 3) in vec3 PreviousPosition;
#else
layout(location = 2) in ivec2 UV2;
layout(location = 3) in vec4 Color;
layout(location = 4) in vec3 PreviousPosition;
uniform sampler2D Sampler2;
#endif

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

#include <metallum:motion_vertex.glsl>
layout(location = 2) out vec4 metallumVertexColor;
layout(location = 3) out vec2 metallumTexCoord;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;

    metallumSetClipHistory(currentClip, previousClip);
#if defined(IS_GUI) || defined(IS_SEE_THROUGH)
    metallumVertexColor = Color;
#else
    metallumVertexColor = Color * sample_lightmap(Sampler2, UV2);
#endif
    metallumTexCoord = UV0;
}
