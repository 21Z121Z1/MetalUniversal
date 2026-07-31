#version 430 compatibility

/* DRAWBUFFERS:01 */

void main() {
    gl_FragData[0] = vec4(0.0, 0.0, 1.0, 0.5);
    gl_FragData[1] = vec4(0.0, 0.0, 1.0, 0.5);
}
