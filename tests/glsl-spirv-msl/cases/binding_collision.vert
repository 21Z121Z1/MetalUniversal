#version 460 core

// Two UBOs with DIFFERENT bindings (0 and 1). With enableDecorationBinding=true, SPIRV-Cross maps them to [[buffer(0)]] and [[buffer(1)]] respectively.
layout(binding=0) uniform PC {
    float pc_value;
};

layout(binding=1) uniform u_Globals {
    float global_value;
};

void main() {
    gl_Position = vec4(pc_value + global_value, 0.0, 0.0, 1.0);
}
