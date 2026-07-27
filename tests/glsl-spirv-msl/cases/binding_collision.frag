#version 460

layout(location=0) out vec4 fragColor;

layout(set=0, binding=0) uniform PC {
    float pc_value;
};

layout(set=1, binding=0) uniform u_Globals {
    float global_value;
};

void main() {
    fragColor = vec4(pc_value + global_value, 0.0, 0.0, 1.0);
}
