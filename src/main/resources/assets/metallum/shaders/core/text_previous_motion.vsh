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

layout(location = 0) noperspective out vec2 metallumObjectMotion;
layout(location = 1) flat out float metallumObjectValidity;
layout(location = 2) out vec4 metallumVertexColor;
layout(location = 3) out vec2 metallumTexCoord;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;

    bool valid = currentClip.w > 1.0e-6 && previousClip.w > 1.0e-6;
    if (valid) {
        vec2 currentNdc = currentClip.xy / currentClip.w;
        vec2 previousNdc = previousClip.xy / previousClip.w;
        vec2 motion = vec2(previousNdc.x - currentNdc.x, currentNdc.y - previousNdc.y);
        valid = !any(isnan(currentNdc)) && !any(isinf(currentNdc))
            && !any(isnan(previousNdc)) && !any(isinf(previousNdc))
            && !any(isnan(motion)) && !any(isinf(motion))
            && all(lessThanEqual(abs(motion), vec2(32.0)));
        metallumObjectMotion = valid ? motion : vec2(0.0);
    } else {
        metallumObjectMotion = vec2(0.0);
    }
    metallumObjectValidity = valid ? 1.0 : 0.0;
#ifdef IS_SEE_THROUGH
    metallumVertexColor = Color;
#else
    metallumVertexColor = Color * sample_lightmap(Sampler2, UV2);
#endif
    metallumTexCoord = UV0;
}
