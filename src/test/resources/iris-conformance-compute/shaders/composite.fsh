#version 430 compatibility

/* DRAWBUFFERS:0 */

uniform sampler2D colortex0;
uniform sampler2D contractSampler;
in vec2 texcoord;

void main() {
    vec3 computeColor = texture(colortex0, texcoord).rgb;
    vec3 customImageColor = texture(contractSampler, texcoord).rgb;
    gl_FragData[0] = vec4(computeColor + customImageColor, 1.0);
}
