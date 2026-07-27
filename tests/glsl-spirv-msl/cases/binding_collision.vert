#version 460 core

// Two UBOs with the SAME binding=0 — simulates Iris's push constants (PC) + global UBO (u_Globals)
// With enableDecorationBinding=true this would produce two [[buffer(0)]] in MSL (collision).
// With enableDecorationBinding=false SPIRV-Cross auto-assigns buffer(0) and buffer(1).
layout(binding=0) uniform PC {
    float pc_value;
};

layout(binding=0) uniform u_Globals {
    float global_value;
};

void main() {
    gl_Position = vec4(pc_value + global_value, 0.0, 0.0, 1.0);
}
