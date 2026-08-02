#version 430 compatibility

/* DRAWBUFFERS:0 */

uniform sampler2D colortex0;
layout(rgba8, binding = 3) uniform image2D contractImage;
layout(std430, binding = 1) buffer ContractState {
    uint words[];
};
in vec2 texcoord;

void main() {
    ivec2 size = imageSize(contractImage);
    ivec2 pixel = clamp(ivec2(texcoord * vec2(size)), ivec2(0), size - ivec2(1));
    if (all(equal(pixel, size - ivec2(1)))) {
        words[5] = 0x99aabbccu;
    }
    imageStore(contractImage, pixel, vec4(0.0, 0.0, 1.0, 1.0));
    gl_FragData[0] = texture(colortex0, texcoord);
}
