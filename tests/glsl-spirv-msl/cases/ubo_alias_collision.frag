#version 460 core

layout(location=0) out vec4 fragColor;

layout(std140, binding=0) uniform iris_Fog {
    vec4 FogColor;
    float FogDensity;
};

layout(std140, binding=1) uniform iris_Globals {
    mat4 iris_ProjMat;
    vec4 iris_ColorModulator;
};

void main() {
    fragColor = iris_ProjMat * vec4(FogColor.rgb * FogDensity, 1.0);
}
