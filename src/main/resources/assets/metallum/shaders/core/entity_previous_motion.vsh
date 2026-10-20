#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#ifdef METALLUM_ENTITY_LIGHTMAP
#include <minecraft:sample_lightmap.glsl>
// ENTITY and ITEM, including GLINT_SPECIAL, both keep UV2 at physical location 4.
layout(location = 4) in ivec2 UV2;
uniform sampler2D Sampler2;
layout(location = 6) out float metallumLightmapAlpha;
#endif

// Binding 0 is the exact Minecraft ENTITY stream. Binding 1 is a compact
// float3 stream captured from the previous successfully submitted source
// frame. ENTITY has six physical attributes, so PreviousPosition is location 6.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#ifdef METALLUM_ENTITY_GLINT_SPECIAL
// ITEM GLINT_SPECIAL also owns UV3, making seven physical source attributes.
layout(location = 7) in vec3 PreviousPosition;
#ifdef METALLUM_PREVIOUS_VALIDITY
layout(location = 8) in float PreviousValidity;
#endif
#else
layout(location = 6) in vec3 PreviousPosition;
#ifdef METALLUM_PREVIOUS_VALIDITY
layout(location = 7) in float PreviousValidity;
#endif
#endif

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
#if defined(APPLY_TEXTURE_MATRIX) && !defined(METALLUM_ITEM_SOURCE)
    metallumTexCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
    metallumVertexColorGuard = Color.a;
    metallumVertexAlpha = Color.a;
#ifdef METALLUM_ENTITY_LIGHTMAP
    metallumLightmapAlpha = sample_lightmap(Sampler2, UV2).a;
#endif
}
