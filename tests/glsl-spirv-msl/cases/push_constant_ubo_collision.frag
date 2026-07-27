#version 450

// Fragment shader also has push constant + UBO to test fragment stage collision
layout(push_constant) uniform PC {
    vec4 u_ColorModulator;
};

layout(std140, binding=0) uniform u_Globals {
    vec4 u_FogColor;
};

layout(location=0) out vec4 fragColor;

void main() {
    fragColor = u_ColorModulator + u_FogColor;
}
