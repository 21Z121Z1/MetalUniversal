#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

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

noperspective out vec2 metallumObjectMotion;
flat out float metallumObjectValidity;
out vec2 metallumTexCoord;
flat out float metallumVertexAlpha;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;
    metallumTexCoord = UV0;
    metallumVertexAlpha = Color.a;

    bool valid = currentClip.w > 1.0e-6 && previousClip.w > 1.0e-6;
    if (valid) {
        vec2 currentNdc = currentClip.xy / currentClip.w;
        vec2 previousNdc = previousClip.xy / previousClip.w;
        vec2 motion = vec2(
            previousNdc.x - currentNdc.x,
            currentNdc.y - previousNdc.y
        );
        valid = !any(isnan(currentNdc)) && !any(isinf(currentNdc))
            && !any(isnan(previousNdc)) && !any(isinf(previousNdc))
            && !any(isnan(motion)) && !any(isinf(motion))
            && all(lessThanEqual(abs(motion), vec2(32.0)));
        metallumObjectMotion = valid ? motion : vec2(0.0);
    } else {
        metallumObjectMotion = vec2(0.0);
    }
    metallumObjectValidity = valid ? 1.0 : 0.0;
}
