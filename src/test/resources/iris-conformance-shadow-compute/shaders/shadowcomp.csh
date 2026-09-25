#version 430

const ivec3 workGroups = ivec3(1, 1, 1);

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
layout(rgba8, binding = 0) uniform writeonly image2D shadowcolorimg0;

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (all(lessThan(pixel, imageSize(shadowcolorimg0)))) {
        imageStore(shadowcolorimg0, pixel, vec4(0.0, 0.5, 1.0, 1.0));
    }
}
