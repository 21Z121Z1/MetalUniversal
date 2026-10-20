#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Binding 0 is decoded from the actual CloudFaces/CloudInfo source draw.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec3 PreviousPosition;
#ifdef METALLUM_PREVIOUS_VALIDITY
layout(location = 3) in float PreviousValidity;
#endif
layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};
#include <metallum:motion_vertex.glsl>
layout(location = 2) out float metallumCloudAlpha;
layout(location = 3) out float metallumCloudDistance;

void main() {
    vec4 rasterClip=ProjMat*ModelViewMat*vec4(Position,1.0);
    gl_Position=rasterClip;
    metallumSetClipHistory(CurrentUnjitteredFromRaster*rasterClip,PreviousFromRaster*vec4(PreviousPosition,1.0));
    metallumCloudAlpha=Color.a;
    metallumCloudDistance=length(Position);
}
