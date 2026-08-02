#version 430 compatibility

uniform sampler2D colortex0;
uniform sampler2D colortex1;
in vec2 texcoord;

void main() {
    vec4 first = texture(colortex0, texcoord);
    vec4 second = texture(colortex1, texcoord);
    if (texcoord.x < 0.25) {
        gl_FragColor = vec4(first.rg, second.b, 1.0);
    } else if (texcoord.x < 0.5) {
        gl_FragColor = vec4(0.18, 0.18, 0.18, 1.0);
    } else if (texcoord.x < 0.75) {
        gl_FragColor = vec4(0.5, 0.5, 0.5, 1.0);
    } else {
        gl_FragColor = vec4(1.25, 1.25, 1.25, 1.0);
    }
}
