#version 430

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
layout(rgba8, binding = 0) uniform readonly image2D shadowcolorimg0;
layout(rgba8, binding = 1) uniform writeonly image2D shadowcolorimg1;

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (all(lessThan(pixel, imageSize(shadowcolorimg0)))) {
        imageStore(shadowcolorimg1, pixel, imageLoad(shadowcolorimg0, pixel));
    }
}
