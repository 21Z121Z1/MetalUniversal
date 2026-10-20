#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Match the packed entity vertex format even though this reduced shader does
// not consume Color at location 1. Without explicit locations SPIR-V assigns
// UV0 to attribute 1, sampling vertex colors as texture coordinates and
// causing the alpha-test replay to discard every fragment.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

#include <metallum:motion_vertex.glsl>
layout(location = 2) out vec2 metallumTexCoord;
layout(location = 3) flat out float metallumVertexColorGuard;
layout(location = 4) out float metallumVertexAlpha;

void main() {
    // Entity model vertices already contain the exact CPU-side PoseStack
    // transforms used by the color pass. Its raster clip position is
    // reconstructed from the same pipeline matrices in the Java-supplied
    // clip transforms, so current jitter can be removed before velocity is
    // measured.
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * rasterClip;
    gl_Position = rasterClip;

    metallumSetClipHistory(currentClip, previousClip);
    metallumTexCoord = UV0;
#if defined(APPLY_TEXTURE_MATRIX) && !defined(METALLUM_ITEM_SOURCE)
    metallumTexCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
    metallumVertexColorGuard = Color.a;
    metallumVertexAlpha = Color.a;
}
