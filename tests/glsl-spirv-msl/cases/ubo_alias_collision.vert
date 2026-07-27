#version 460 core

// Two UBOs with explicit DIFFERENT bindings — simulates the post-fix state
// where MetalIrisBridge.assignUniqueUboBindings has injected binding=0 and binding=1.
// SPIRV-Cross should NOT alias them to a single void* [[buffer(0)]].
layout(std140, binding=0) uniform iris_Fog {
    vec4 FogColor;
    float FogDensity;
};

layout(std140, binding=1) uniform iris_Globals {
    mat4 iris_ProjMat;
    vec4 iris_ColorModulator;
};

void main() {
    gl_Position = iris_ProjMat * vec4(FogColor.rgb * FogDensity, 1.0);
}
