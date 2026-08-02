#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Match the packed block vertex format even though this reduced shader does not
// consume Color at location 1. Without explicit locations SPIR-V assigns UV0 to
// attribute 1, sampling vertex colors as texture coordinates and causing the
// alpha-test replay to discard every fragment. DefaultVertexFormat.BLOCK also
// carries an ivec2 UV2 at location 3; it is left undeclared because this shader
// never reads the lightmap, and an undeclared trailing attribute is simply
// unused by the vertex binding the source pipeline supplies.
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
    // The one thing that separates this family from core/entity: block geometry
    // is emitted relative to a per-draw origin and offset by ModelOffset in the
    // color pass, so its raster clip position is only reproducible with the same
    // term added here. ModelOffset lives in the shared DynamicTransforms block
    // that the source pipeline already binds, so no extra uniform is needed and
    // the value is by construction the one the color pass used.
    vec3 pos = Position + ModelOffset;
    vec4 rasterClip = ProjMat * ModelViewMat * vec4(pos, 1.0);
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
