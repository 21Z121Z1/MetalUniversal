#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
uniform sampler2D Sampler2;
// BLOCK has four source attributes. The previous stream already includes the
// previous accepted draw's ModelOffset, independently of the current offset.
layout(location = 4) in vec3 PreviousPosition;
#ifdef METALLUM_PREVIOUS_VALIDITY
layout(location = 5) in float PreviousValidity;
#endif

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

#include <metallum:motion_vertex.glsl>
layout(location = 2) out vec2 metallumTexCoord;
layout(location = 3) flat out float metallumVertexColorGuard;
layout(location = 4) out float metallumVertexAlpha;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position + ModelOffset, 1.0);
    gl_Position = rasterClip;
    metallumSetClipHistory(CurrentUnjitteredFromRaster * rasterClip,
            PreviousFromRaster * vec4(PreviousPosition, 1.0));
    metallumTexCoord = UV0;
    metallumVertexColorGuard = Color.a;
    metallumVertexAlpha = Color.a * sample_lightmap(Sampler2, UV2).a;
}
