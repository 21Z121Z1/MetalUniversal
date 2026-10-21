#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec3 PreviousPosition;
#ifdef METALLUM_PREVIOUS_VALIDITY
layout(location = 3) in float PreviousValidity;
#endif

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

#include <metallum:motion_vertex.glsl>
layout(location = 2) out float metallumVertexAlpha;
layout(location = 3) out vec2 metallumFogDistances;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = rasterClip;
    metallumSetClipHistory(CurrentUnjitteredFromRaster * rasterClip,
                           PreviousFromRaster * vec4(PreviousPosition, 1.0));
    metallumVertexAlpha = Color.a;
    metallumFogDistances = vec2(fog_spherical_distance(Position), fog_cylindrical_distance(Position));
}
