#version 430

const ivec3 workGroups = ivec3(1, 1, 1);

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
layout(rgba8, binding = 0) uniform writeonly image2D colorimg0;
layout(rgba8, binding = 4) uniform writeonly image2D colorimg1;
layout(rgba8, binding = 3) uniform writeonly image2D contractImage;
layout(std430, binding = 1) buffer ContractState {
    uint words[];
};

void main() {
    ivec2 size = imageSize(colorimg0);
    words[0] = uint((size.x + 3) / 4);
    words[1] = uint((size.y + 3) / 4);
    words[2] = 1u;
    words[3] = 0x11223344u;
    words[4] = 0u;
    for (int y = 0; y < size.y; ++y) {
        for (int x = 0; x < size.x; ++x) {
            imageStore(colorimg0, ivec2(x, y), vec4(1.0, 0.0, 0.0, 1.0));
            imageStore(colorimg1, ivec2(x, y), vec4(0.0, 1.0, 0.0, 1.0));
            imageStore(contractImage, ivec2(x, y), vec4(1.0, 0.0, 0.0, 1.0));
        }
    }
}
