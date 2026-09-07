#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Binding 0 is the exact Minecraft ENTITY stream. Binding 1 is a compact
// float3 stream captured from the previous successfully submitted source
// frame. ENTITY has six physical attributes, so PreviousPosition is location 6.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 6) in vec3 PreviousPosition;

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    // For this shader this is the previous source frame's unjittered
    // camera-relative view-projection (Projection * viewRotation). Previous
    // staged positions are already camera-relative, so no world-sized camera
    // translation is added here.
    mat4 PreviousFromRaster;
};

noperspective out vec2 metallumObjectMotion;
flat out float metallumObjectValidity;
out vec2 metallumTexCoord;
flat out float metallumVertexColorGuard;

void main() {
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousClip = PreviousFromRaster * vec4(PreviousPosition, 1.0);
    gl_Position = rasterClip;

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
    metallumTexCoord = UV0;
    metallumVertexColorGuard = Color.a;
}
