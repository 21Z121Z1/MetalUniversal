#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Preserve the original GPU expression. Baking current positions on the CPU
// changes floating-point contraction and can fail the exact source-depth test.
layout(std140) uniform CloudInfo {
    vec4 CloudColor;
    vec3 CloudOffset;
    vec3 CellSize;
};
uniform isamplerBuffer CloudFaces;
layout(location = 0) in vec3 PreviousPosition;
#ifdef METALLUM_PREVIOUS_VALIDITY
layout(location = 1) in float PreviousValidity;
#endif
layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};
#include <metallum:motion_vertex.glsl>
layout(location = 2) out float metallumCloudAlpha;
layout(location = 3) out float metallumCloudDistance;

vec3 cloudVertex(int index) {
    vec3 pos=vec3(0);
    pos.x=float((0xF03CC3 >> index)&1);
    pos.y=float((0x6666F0 >> index)&1);
    pos.z=float((0xC3F066 >> index)&1);
    return pos;
}

void main() {
    int quadVertex=gl_VertexIndex%4;
    int index=(gl_VertexIndex/4)*3;
    int cellX=texelFetch(CloudFaces,index).r;
    int cellZ=texelFetch(CloudFaces,index+1).r;
    int flags=texelFetch(CloudFaces,index+2).r;
    cellX=(cellX<<1)|((flags&128)>>7);
    cellZ=(cellZ<<1)|((flags&64)>>6);
    vec3 faceVertex=cloudVertex((flags&7)*4+((flags&16)!=0 ? 3-quadVertex : quadVertex));
    vec3 pos=(faceVertex*CellSize)+(vec3(cellX,0,cellZ)*CellSize)+CloudOffset;
    vec4 rasterClip=ProjMat*ModelViewMat*vec4(pos,1.0);
    gl_Position=rasterClip;
    metallumSetClipHistory(CurrentUnjitteredFromRaster*rasterClip,PreviousFromRaster*vec4(PreviousPosition,1.0));
    metallumCloudAlpha=CloudColor.a;
    metallumCloudDistance=length(pos);
}
