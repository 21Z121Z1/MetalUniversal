#version 330

#ifndef IS_SEE_THROUGH
#moj_import <minecraft:sample_lightmap.glsl>
#endif
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
#ifdef IS_SEE_THROUGH
layout(location = 2) in vec3 PreviousPosition;
#else
layout(location = 2) in ivec2 UV2;
layout(location = 3) in vec3 PreviousPosition;
uniform sampler2D Sampler2;
#endif

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

noperspective out vec2 metallumObjectMotion;
flat out float metallumObjectValidity;
out vec4 metallumVertexColor;

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
}
