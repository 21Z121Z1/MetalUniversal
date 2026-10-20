#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Minecraft 26.2 DefaultVertexFormat.PARTICLE is Position, UV0, Color, UV2. Binding 1 therefore
// starts PreviousPosition at location 4. The current staged Position is already camera-relative.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 4) in vec3 PreviousPosition;

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

#include <metallum:motion_vertex.glsl>
layout(location = 2) out vec2 metallumTexCoord;
layout(location = 3) out float metallumVertexAlpha;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;
    metallumTexCoord = UV0;
    metallumVertexAlpha = Color.a;

    metallumSetClipHistory(currentClip, previousClip);
}
