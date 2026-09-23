#version 430 compatibility

#define OPTION_COLOR
#define OPTION_LEVEL 1 // [0 1 2]

void main() {
#if OPTION_LEVEL == 2
    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
#elif OPTION_LEVEL == 1
    gl_FragColor = vec4(0.0, 0.0, 1.0, 1.0);
#else
    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
#endif
#ifdef OPTION_COLOR
    gl_FragColor.rgb = gl_FragColor.bgr;
#endif
}
