#version 430

layout(local_size_x = 4, local_size_y = 4, local_size_z = 1) in;
layout(rgba8, binding = 0) uniform writeonly image2D colorimg0;
layout(std430, binding = 1) buffer ContractState {
    uint words[];
};

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (all(lessThan(pixel, imageSize(colorimg0)))) {
        imageStore(colorimg0, pixel, vec4(0.0, 1.0, 0.0, 1.0));
        if (all(equal(pixel, imageSize(colorimg0) - ivec2(1)))) {
            words[4] = 0x55667788u;
        }
    }
}
