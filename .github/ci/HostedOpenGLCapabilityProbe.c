#define GL_SILENCE_DEPRECATION
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl3.h>
#include <stdio.h>
#include <stdlib.h>

static void json_string(const char *value) {
    putchar('"');
    for (const unsigned char *p = (const unsigned char *)(value ? value : ""); *p; ++p) {
        if (*p == '"' || *p == '\\') printf("\\%c", *p);
        else if (*p < 32) printf("\\u%04x", *p);
        else putchar(*p);
    }
    putchar('"');
}

static int blocked(const char *stage, CGLError error) {
    printf("{\"schemaVersion\":1,\"status\":\"blocked-environment\",\"stage\":");
    json_string(stage);
    printf(",\"reason\":"); json_string(CGLErrorString(error)); puts("}");
    return 78;
}

static GLuint shader(GLenum type, const char *source) {
    GLuint handle = glCreateShader(type);
    glShaderSource(handle, 1, &source, NULL);
    glCompileShader(handle);
    GLint ok = GL_FALSE;
    glGetShaderiv(handle, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096] = {0};
        glGetShaderInfoLog(handle, sizeof(log), NULL, log);
        fprintf(stderr, "GLSL compilation failed: %s\n", log);
        glDeleteShader(handle);
        return 0;
    }
    return handle;
}

int main(void) {
    CGLPixelFormatAttribute attributes[] = {
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
        kCGLPFAAccelerated, (CGLPixelFormatAttribute)0
    };
    CGLPixelFormatObj format = NULL;
    CGLContextObj context = NULL;
    GLint count = 0;
    CGLError error = CGLChoosePixelFormat(attributes, &format, &count);
    if (error != kCGLNoError || !format || !count)
        return blocked("choose-core-pixel-format", error == kCGLNoError ? kCGLBadPixelFormat : error);
    error = CGLCreateContext(format, NULL, &context);
    CGLDestroyPixelFormat(format);
    if (error != kCGLNoError || !context) return blocked("create-core-context", error);
    error = CGLSetCurrentContext(context);
    if (error != kCGLNoError) { CGLDestroyContext(context); return blocked("make-current", error); }

    GLuint texture = 0, framebuffer = 0, vao = 0;
    glGenTextures(1, &texture); glBindTexture(GL_TEXTURE_2D, texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glGenFramebuffers(1, &framebuffer); glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    const int complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    GLuint vertex = shader(GL_VERTEX_SHADER,
        "#version 150\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0.,1.);}");
    GLuint fragment = shader(GL_FRAGMENT_SHADER,
        "#version 150\nout vec4 color;void main(){color=vec4(0.,1.,0.,1.);}");
    GLuint program = glCreateProgram();
    if (vertex) glAttachShader(program, vertex);
    if (fragment) glAttachShader(program, fragment);
    glLinkProgram(program);
    GLint linked = GL_FALSE; glGetProgramiv(program, GL_LINK_STATUS, &linked);
    unsigned char pixels[8 * 8 * 4] = {0};
    if (complete && vertex && fragment && linked) {
        glUseProgram(program); glGenVertexArrays(1, &vao); glBindVertexArray(vao);
        glViewport(0, 0, 8, 8); glDisable(GL_DITHER); glDisable(GL_FRAMEBUFFER_SRGB);
        glClearColor(1, 0, 1, 1); glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glReadPixels(0, 0, 8, 8, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }
    GLenum gl_error = glGetError();
    int mismatches = 0;
    for (size_t i = 0; i < sizeof(pixels); ++i)
        mismatches += pixels[i] != ((i % 4 == 1 || i % 4 == 3) ? 255 : 0);
    int passed = complete && vertex && fragment && linked && gl_error == GL_NO_ERROR && !mismatches;
    printf("{\"schemaVersion\":1,\"status\":\"%s\",\"backend\":\"OpenGL\",\"renderer\":", passed ? "passed" : "correctness-failed");
    json_string((const char *)glGetString(GL_RENDERER)); printf(",\"vendor\":");
    json_string((const char *)glGetString(GL_VENDOR)); printf(",\"version\":");
    json_string((const char *)glGetString(GL_VERSION)); printf(",\"glslVersion\":");
    json_string((const char *)glGetString(GL_SHADING_LANGUAGE_VERSION));
    printf(",\"framebufferComplete\":%s,\"programLinked\":%s,\"byteCount\":256,\"mismatchBytes\":%d,\"glError\":%u}\n",
        complete ? "true" : "false", linked ? "true" : "false", mismatches, gl_error);
    glDeleteVertexArrays(1, &vao); glDeleteProgram(program);
    if (vertex) glDeleteShader(vertex);
    if (fragment) glDeleteShader(fragment);
    glDeleteFramebuffers(1, &framebuffer); glDeleteTextures(1, &texture);
    CGLSetCurrentContext(NULL); CGLDestroyContext(context);
    return passed ? EXIT_SUCCESS : EXIT_FAILURE;
}
