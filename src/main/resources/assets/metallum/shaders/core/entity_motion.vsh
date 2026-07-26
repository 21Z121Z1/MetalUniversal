#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

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

noperspective out vec2 metallumObjectMotion;
flat out float metallumObjectValidity;
out vec2 metallumTexCoord;
flat out float metallumVertexColorGuard;

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
