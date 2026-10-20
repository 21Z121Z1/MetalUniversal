#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Binding 0 is the exact Minecraft ENTITY stream. Binding 1 is a compact
// float3 stream captured from the previous successfully submitted source
// frame. ENTITY has six physical attributes, so PreviousPosition is location 6.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 6) in vec3 PreviousPosition;

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    // Previous source transform for this vertex stream: each draw uses its
    // actual unjittered projection * ModelViewMat; first-person draws retain
    // their independent HUD projection. Both include the final clip-Y
    // conversion. Posed previous positions need no world-sized translation.
    mat4 PreviousFromRaster;
};

#include <metallum:motion_vertex.glsl>
layout(location = 2) out vec2 metallumTexCoord;
layout(location = 3) flat out float metallumVertexColorGuard;
layout(location = 4) out float metallumVertexAlpha;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;

    metallumSetClipHistory(currentClip, previousClip);
    metallumTexCoord = UV0;
#ifdef APPLY_TEXTURE_MATRIX
    metallumTexCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
    metallumVertexColorGuard = Color.a;
    metallumVertexAlpha = Color.a;
}
