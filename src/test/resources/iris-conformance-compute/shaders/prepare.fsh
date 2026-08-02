#version 430 compatibility

/* DRAWBUFFERS:1 */

uniform sampler2D colortex1;
in vec2 texcoord;

void main() {
    vec4 previous = texture(colortex1, texcoord);
    gl_FragData[0] = vec4(previous.r, 1.0, 0.0, 1.0);
}
