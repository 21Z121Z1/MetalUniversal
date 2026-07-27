#version 450

// Push constant block — Vulkan 禁止带 binding，SPIRV-Cross 默认分配到 buffer(0)
layout(push_constant) uniform PC {
    mat4 u_ModelViewMat;
};

// UBO with explicit binding=0 — decoration binding=true 时映射到 buffer(0)
// 与 push constant 冲突：两者都在 [[buffer(0)]]
layout(std140, binding=0) uniform u_Globals {
    mat4 u_ProjMat;
};

void main() {
    gl_Position = u_ProjMat * u_ModelViewMat * vec4(0.0, 0.0, 0.0, 1.0);
}
