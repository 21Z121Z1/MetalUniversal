#version 430

const vec2 workGroupsRender = vec2(1.0, 1.0);

layout(local_size_x = 4, local_size_y = 4, local_size_z = 1) in;
layout(rgba8, binding = 0) uniform readonly image2D colorimg0;
layout(std430, binding = 1) buffer ContractState {
    uint words[];
};
layout(std430, binding = 2) buffer RelativePixels {
    uint pixels[];
};

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(colorimg0);
    if (all(lessThan(pixel, size))) {
        uint index = uint(pixel.y * size.x + pixel.x);
        pixels[index] = index + 1u;
        if (index == 0u && imageLoad(colorimg0, pixel).g > 0.5) {
            words[6] = 0xcafebabeu;
        }
    }
}
