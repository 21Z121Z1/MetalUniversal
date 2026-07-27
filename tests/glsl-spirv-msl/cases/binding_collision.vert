#version 460 core

// Two UBOs at the SAME binding=0 but DIFFERENT descriptor sets.
// With enableDecorationBinding=true both map to [[buffer(0)]] (Metal compile error).
// With enableDecorationBinding=false SPIRV-Cross auto-assigns [[buffer(0)]] and [[buffer(1)]].
layout(set=0, binding=0) uniform PC {
    float pc_value;
};

layout(set=1, binding=0) uniform u_Globals {
    float global_value;
};

void main() {
    gl_Position = vec4(pc_value + global_value, 0.0, 0.0, 1.0);
}
