/*
 * MetallumShaderBridge.c — JNI bridge between Java (ShaderBridge) and the
 * glslang (GLSL→SPIR-V) + SPIRV-Cross (SPIR-V→MSL) C APIs.
 *
 * Compiled into libmetallum.dylib alongside MetallumNative.swift (see
 * build.gradle's buildMacNative / buildIOSNative tasks). The JNI symbols
 * are bound by name to the `native` methods declared in ShaderBridge.java,
 * so the JVM resolves them automatically once libmetallum.dylib is loaded
 * via System.load (handled by MetalNativeBridge.ensureShaderLibrariesLoaded).
 *
 * Runtime library dependencies (libglslang.dylib, libspvc.dylib) MUST be
 * loaded before libmetallum.dylib so the dynamic linker can resolve the
 * glslang_* and spvc_* symbols referenced below.
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* JNI header — provided by the JDK. */
#include <jni.h>

/* glslang C API (opaque types + glslang_input_t + glslang_stage_t etc.) */
#include "glslang/Include/glslang_c_interface.h"
/* glslang_default_resource() — default TBuiltInResource for Vulkan */
#include "glslang/Public/resource_limits_c.h"

/* SPIRV-Cross C API (spvc_context / spvc_compiler / options) */
#include "spirv_cross_c.h"

/* -------------------------------------------------------------------------
 * glslang one-time initialization
 * -------------------------------------------------------------------------
 * glslang_initialize_process() must be called exactly once before any
 * shader compilation. A static flag + C11 atomic-free guard is sufficient
 * here because the JVM loads this library once and the static block in
 * MetalNativeBridge serialises the first call.
 */
static int g_glslang_initialized = 0;

static int ensure_glslang_initialized(void) {
    if (g_glslang_initialized) {
        return 1;
    }
    if (glslang_initialize_process()) {
        g_glslang_initialized = 1;
        return 1;
    }
    return 0;
}

/* -------------------------------------------------------------------------
 * Helper: throw a java.lang.RuntimeException with a UTF-8 message.
 * Safe to call with an already-pending exception (it clears nothing; the
 * JVM reports the first pending exception).
 */
static void throw_runtime_exception(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) {
        /* A JNI exception is already pending; don't mask it. */
        return;
    }
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls == NULL) {
        return; /* ClassNotFoundException will surface instead */
    }
    (*env)->ThrowNew(env, cls, message ? message : "unknown native error");
    (*env)->DeleteLocalRef(env, cls);
}

/* -------------------------------------------------------------------------
 * Map the Java-side stage integer (ShaderBridge constants) to glslang's
 * glslang_stage_t enum.
 *
 *   Java 0 = VERTEX   → GLSLANG_STAGE_VERTEX   (0)
 *   Java 1 = FRAGMENT → GLSLANG_STAGE_FRAGMENT (4)
 *   Java 2 = GEOMETRY → GLSLANG_STAGE_GEOMETRY (3)
 *   Java 3 = COMPUTE  → GLSLANG_STAGE_COMPUTE  (5)
 *
 * (See glslang_c_shader_types.h for the enum values.)
 */
static glslang_stage_t map_stage(jint stage) {
    switch (stage) {
        case 0: return GLSLANG_STAGE_VERTEX;
        case 1: return GLSLANG_STAGE_FRAGMENT;
        case 2: return GLSLANG_STAGE_GEOMETRY;
        case 3: return GLSLANG_STAGE_COMPUTE;
        default: return (glslang_stage_t)-1; /* invalid */
    }
}

/* Map the Java-side target integer to glslang's client version enum. */
static glslang_target_client_version_t map_target(jint target) {
    switch (target) {
        case 0: return GLSLANG_TARGET_VULKAN_1_0;
        case 1: return GLSLANG_TARGET_VULKAN_1_1;
        case 2: return GLSLANG_TARGET_VULKAN_1_2;
        default: return (glslang_target_client_version_t)0; /* invalid */
    }
}

/* -------------------------------------------------------------------------
 * JNI: ShaderBridge.glslangCompile(String source, int stage, int target)
 *
 * Compiles a GLSL source string to a SPIR-V binary using glslang's C API.
 * Returns a jbyteArray containing the raw SPIR-V words (little-endian bytes).
 * Throws java.lang.RuntimeException on failure, with the glslang info log.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_metallum_client_metal_render_bridge_ShaderBridge_glslangCompile(
    JNIEnv *env, jclass cls, jstring jSource, jint stage, jint target) {

    (void)cls; /* unused — static method */

    /* --- Extract the GLSL source string from Java ---------------------- */
    const char *source = (*env)->GetStringUTFChars(env, jSource, NULL);
    if (source == NULL) {
        /* OutOfMemoryError already pending. */
        return NULL;
    }

    glslang_stage_t glslang_stage = map_stage(stage);
    if ((int)glslang_stage < 0) {
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        throw_runtime_exception(env, "glslangCompile: invalid shader stage");
        return NULL;
    }

    glslang_target_client_version_t client_version = map_target(target);
    if (target < 0 || target > 2) {
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        throw_runtime_exception(env, "glslangCompile: invalid SPIR-V target");
        return NULL;
    }

    /* --- One-time glslang init ------------------------------------------ */
    if (!ensure_glslang_initialized()) {
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        throw_runtime_exception(env, "glslangCompile: glslang_initialize_process() failed");
        return NULL;
    }

    /* --- Build the glslang_input_t -------------------------------------- */
    glslang_input_t input;
    memset(&input, 0, sizeof(input));
    input.language = GLSLANG_SOURCE_GLSL;
    input.stage = glslang_stage;
    input.client = GLSLANG_CLIENT_VULKAN;
    input.client_version = client_version;
    input.target_language = GLSLANG_TARGET_SPV;
    input.target_language_version = GLSLANG_TARGET_SPV_1_5;
    input.code = source;
    input.default_version = 100;
    input.default_profile = GLSLANG_NO_PROFILE;
    input.force_default_version_and_profile = 0;
    input.forward_compatible = 0;
    input.messages = (glslang_messages_t)(
        GLSLANG_MSG_SPV_RULES_BIT | GLSLANG_MSG_VULKAN_RULES_BIT);
    input.resource = glslang_default_resource();

    /* --- Create shader, preprocess, parse -------------------------------- */
    glslang_shader_t *shader = glslang_shader_create(&input);
    if (shader == NULL) {
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        throw_runtime_exception(env, "glslangCompile: glslang_shader_create returned NULL");
        return NULL;
    }

    if (!glslang_shader_preprocess(shader, &input)) {
        const char *log = glslang_shader_get_info_log(shader);
        char msg[2048];
        snprintf(msg, sizeof(msg), "glslangCompile: preprocess failed.\n%s",
                 log ? log : "(no info log)");
        glslang_shader_delete(shader);
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    if (!glslang_shader_parse(shader, &input)) {
        const char *log = glslang_shader_get_info_log(shader);
        char msg[2048];
        snprintf(msg, sizeof(msg), "glslangCompile: parse failed.\n%s",
                 log ? log : "(no info log)");
        glslang_shader_delete(shader);
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    /* --- Link into a program and generate SPIR-V ------------------------ */
    glslang_program_t *program = glslang_program_create();
    if (program == NULL) {
        glslang_shader_delete(shader);
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        throw_runtime_exception(env, "glslangCompile: glslang_program_create returned NULL");
        return NULL;
    }

    glslang_program_add_shader(program, shader);

    if (!glslang_program_link(program,
            GLSLANG_MSG_SPV_RULES_BIT | GLSLANG_MSG_VULKAN_RULES_BIT)) {
        const char *log = glslang_program_get_info_log(program);
        char msg[2048];
        snprintf(msg, sizeof(msg), "glslangCompile: link failed.\n%s",
                 log ? log : "(no info log)");
        glslang_program_delete(program);
        glslang_shader_delete(shader);
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    glslang_program_SPIRV_generate(program, glslang_stage);

    /* --- Extract the SPIR-V binary --------------------------------------- */
    /* glslang_program_SPIRV_get_size returns the word count (uint32_t words). */
    size_t word_count = glslang_program_SPIRV_get_size(program);
    if (word_count == 0) {
        const char *msg = glslang_program_SPIRV_get_messages(program);
        char err[2048];
        snprintf(err, sizeof(err),
                 "glslangCompile: SPIR-V generation produced 0 words.\n%s",
                 msg ? msg : "(no SPIR-V messages)");
        glslang_program_delete(program);
        glslang_shader_delete(shader);
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        throw_runtime_exception(env, err);
        return NULL;
    }

    uint32_t *spirv_words = (uint32_t *)malloc(word_count * sizeof(uint32_t));
    if (spirv_words == NULL) {
        glslang_program_delete(program);
        glslang_shader_delete(shader);
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        throw_runtime_exception(env, "glslangCompile: out of memory allocating SPIR-V buffer");
        return NULL;
    }

    glslang_program_SPIRV_get(program, spirv_words);

    /* --- Copy SPIR-V bytes into a jbyteArray ---------------------------- */
    size_t byte_count = word_count * sizeof(uint32_t);
    jbyteArray result = (*env)->NewByteArray(env, (jsize)byte_count);
    if (result == NULL) {
        /* OutOfMemoryError pending. */
        free(spirv_words);
        glslang_program_delete(program);
        glslang_shader_delete(shader);
        (*env)->ReleaseStringUTFChars(env, jSource, source);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)byte_count, (const jbyte *)spirv_words);

    /* Check for array region overflow exception (e.g. out of bounds). */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteLocalRef(env, result);
        result = NULL;
    }

    /* --- Cleanup -------------------------------------------------------- */
    free(spirv_words);
    glslang_program_delete(program);
    glslang_shader_delete(shader);
    (*env)->ReleaseStringUTFChars(env, jSource, source);

    return result;
}

/* -------------------------------------------------------------------------
 * JNI: ShaderBridge.spvcCompileToMsl(byte[] spirv, int mslPlatform,
 *          int mslVersion, boolean enableDecorationBinding,
 *          boolean textureBufferNative, boolean flipVertexY)
 *
 * Cross-compiles a SPIR-V binary to Metal Shading Language (MSL) using
 * SPIRV-Cross's C API. Returns the MSL source as a Java String.
 * Throws java.lang.RuntimeException on failure.
 *
 * The SPIR-V word count is derived from the byte array length (bytes / 4).
 */
JNIEXPORT jstring JNICALL
Java_com_metallum_client_metal_render_bridge_ShaderBridge_spvcCompileToMsl(
    JNIEnv *env, jclass cls, jbyteArray jSpirv,
    jint mslPlatform, jint mslVersion, jboolean enableDecorationBinding,
    jboolean textureBufferNative, jboolean flipVertexY) {

    (void)cls; /* unused — static method */

    if (jSpirv == NULL) {
        throw_runtime_exception(env, "spvcCompileToMsl: spirv byte array is null");
        return NULL;
    }

    jsize byte_length = (*env)->GetArrayLength(env, jSpirv);
    if (byte_length < (jsize)(4 * sizeof(uint32_t))) {
        /* A SPIR-V module has a 20-byte header (5 words) minimum. */
        throw_runtime_exception(env, "spvcCompileToMsl: SPIR-V byte array too small to be valid");
        return NULL;
    }
    if ((byte_length % 4) != 0) {
        throw_runtime_exception(env, "spvcCompileToMsl: SPIR-V byte length is not a multiple of 4");
        return NULL;
    }

    size_t word_count = (size_t)byte_length / sizeof(uint32_t);

    /* --- Copy the SPIR-V bytes from Java -------------------------------- */
    /* Use GetByteArrayElements (may copy or pin). SPIRV-Cross parses the
     * SPIR-V in one shot inside spvc_context_parse_spirv, so the release
     * can happen right after. */
    jbyte *spirv_bytes = (*env)->GetByteArrayElements(env, jSpirv, NULL);
    if (spirv_bytes == NULL) {
        /* OutOfMemoryError pending. */
        return NULL;
    }

    const SpvId *spirv_words = (const SpvId *)spirv_bytes;

    /* --- Create the SPIRV-Cross context --------------------------------- */
    spvc_context context = NULL;
    spvc_result rc = spvc_context_create(&context);
    if (rc != SPVC_SUCCESS || context == NULL) {
        (*env)->ReleaseByteArrayElements(env, jSpirv, spirv_bytes, JNI_ABORT);
        throw_runtime_exception(env, "spvcCompileToMsl: spvc_context_create failed");
        return NULL;
    }

    /* --- Parse the SPIR-V ------------------------------------------------ */
    spvc_parsed_ir parsed_ir = NULL;
    rc = spvc_context_parse_spirv(context, spirv_words, word_count, &parsed_ir);
    if (rc != SPVC_SUCCESS) {
        const char *err = spvc_context_get_last_error_string(context);
        char msg[2048];
        snprintf(msg, sizeof(msg),
                 "spvcCompileToMsl: spvc_context_parse_spirv failed (%d).\n%s",
                 (int)rc, err ? err : "(no error string)");
        spvc_context_destroy(context);
        (*env)->ReleaseByteArrayElements(env, jSpirv, spirv_bytes, JNI_ABORT);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    /* The IR has been copied (SPVC_CAPTURE_MODE_COPY below) so we can
     * release the Java byte array now. */
    (*env)->ReleaseByteArrayElements(env, jSpirv, spirv_bytes, JNI_ABORT);
    spirv_bytes = NULL;
    spirv_words = NULL;

    /* --- Create the MSL compiler ----------------------------------------- */
    spvc_compiler compiler = NULL;
    rc = spvc_context_create_compiler(context, SPVC_BACKEND_MSL,
                                      parsed_ir, SPVC_CAPTURE_MODE_COPY, &compiler);
    if (rc != SPVC_SUCCESS || compiler == NULL) {
        const char *err = spvc_context_get_last_error_string(context);
        char msg[2048];
        snprintf(msg, sizeof(msg),
                 "spvcCompileToMsl: spvc_context_create_compiler(MSL) failed (%d).\n%s"
                 "\n(This usually means libspvc.dylib was built without the MSL backend.)",
                 (int)rc, err ? err : "(no error string)");
        spvc_context_destroy(context);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    /* --- Configure MSL compiler options --------------------------------- */
    /*   MSL_PLATFORM:  Java 0 = MACOS → SPVC_MSL_PLATFORM_MACOS (1)
     *                  Java 1 = IOS   → SPVC_MSL_PLATFORM_IOS   (0)
     *   (SPVC_MSL_PLATFORM_IOS=0, SPVC_MSL_PLATFORM_MACOS=1 in spirv_cross_c.h)
     */
    spvc_compiler_options options = NULL;
    rc = spvc_compiler_create_compiler_options(compiler, &options);
    if (rc != SPVC_SUCCESS || options == NULL) {
        const char *err = spvc_context_get_last_error_string(context);
        char msg[2048];
        snprintf(msg, sizeof(msg),
                 "spvcCompileToMsl: spvc_compiler_create_compiler_options failed (%d).\n%s",
                 (int)rc, err ? err : "(no error string)");
        spvc_context_destroy(context);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    unsigned spvc_platform = (mslPlatform == 1)
        ? SPVC_MSL_PLATFORM_IOS
        : SPVC_MSL_PLATFORM_MACOS;

    rc = spvc_compiler_options_set_uint(options,
        SPVC_COMPILER_OPTION_MSL_PLATFORM, spvc_platform);
    if (rc != SPVC_SUCCESS) {
        const char *err = spvc_context_get_last_error_string(context);
        char msg[2048];
        snprintf(msg, sizeof(msg),
                 "spvcCompileToMsl: set_uint(MSL_PLATFORM) failed (%d).\n%s",
                 (int)rc, err ? err : "(no error string)");
        spvc_context_destroy(context);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    rc = spvc_compiler_options_set_uint(options,
        SPVC_COMPILER_OPTION_MSL_VERSION, (unsigned)mslVersion);
    if (rc != SPVC_SUCCESS) {
        const char *err = spvc_context_get_last_error_string(context);
        char msg[2048];
        snprintf(msg, sizeof(msg),
                 "spvcCompileToMsl: set_uint(MSL_VERSION) failed (%d).\n%s",
                 (int)rc, err ? err : "(no error string)");
        spvc_context_destroy(context);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    rc = spvc_compiler_options_set_bool(options,
        SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING,
        enableDecorationBinding ? SPVC_TRUE : SPVC_FALSE);
    if (rc != SPVC_SUCCESS) {
        const char *err = spvc_context_get_last_error_string(context);
        char msg[2048];
        snprintf(msg, sizeof(msg),
                 "spvcCompileToMsl: set_bool(MSL_ENABLE_DECORATION_BINDING) failed (%d).\n%s",
                 (int)rc, err ? err : "(no error string)");
        spvc_context_destroy(context);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    rc = spvc_compiler_options_set_bool(options,
        SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE,
        textureBufferNative ? SPVC_TRUE : SPVC_FALSE);
    if (rc != SPVC_SUCCESS) {
        const char *err = spvc_context_get_last_error_string(context);
        char msg[2048];
        snprintf(msg, sizeof(msg),
                 "spvcCompileToMsl: set_bool(MSL_TEXTURE_BUFFER_NATIVE) failed (%d).\n%s",
                 (int)rc, err ? err : "(no error string)");
        spvc_context_destroy(context);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    rc = spvc_compiler_options_set_bool(options,
        SPVC_COMPILER_OPTION_FLIP_VERTEX_Y,
        flipVertexY ? SPVC_TRUE : SPVC_FALSE);
    if (rc != SPVC_SUCCESS) {
        const char *err = spvc_context_get_last_error_string(context);
        char msg[2048];
        snprintf(msg, sizeof(msg),
                 "spvcCompileToMsl: set_bool(FLIP_VERTEX_Y) failed (%d).\n%s",
                 (int)rc, err ? err : "(no error string)");
        spvc_context_destroy(context);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    rc = spvc_compiler_install_compiler_options(compiler, options);
    if (rc != SPVC_SUCCESS) {
        const char *err = spvc_context_get_last_error_string(context);
        char msg[2048];
        snprintf(msg, sizeof(msg),
                 "spvcCompileToMsl: spvc_compiler_install_compiler_options failed (%d).\n%s",
                 (int)rc, err ? err : "(no error string)");
        spvc_context_destroy(context);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    /* --- Compile to MSL ------------------------------------------------- */
    const char *msl_source = NULL;
    rc = spvc_compiler_compile(compiler, &msl_source);
    if (rc != SPVC_SUCCESS || msl_source == NULL) {
        const char *err = spvc_context_get_last_error_string(context);
        char msg[2048];
        snprintf(msg, sizeof(msg),
                 "spvcCompileToMsl: spvc_compiler_compile failed (%d).\n%s",
                 (int)rc, err ? err : "(no error string)");
        spvc_context_destroy(context);
        throw_runtime_exception(env, msg);
        return NULL;
    }

    /* --- Return the MSL source as a Java String -------------------------- */
    /* msl_source is owned by the spvc_context arena; copy it into a
     * jstring before destroying the context. NewStringUTF makes its own
     * internal copy of the UTF-8 bytes. */
    jstring result = (*env)->NewStringUTF(env, msl_source);

    /* --- Cleanup -------------------------------------------------------- */
    /* spvc_context_destroy frees the compiler, options, parsed_ir, and the
     * MSL source string (all allocated in the context's arena). */
    spvc_context_destroy(context);

    return result;
}

/* -------------------------------------------------------------------------
 * 声明 Swift 导出的 metallum_get_last_native_error（@_cdecl 符号）。
 * 返回指向静态缓冲区的 const char*，内容为最近一次
 * metallum_create_shader_function 失败的错误详情。
 */
extern const char *metallum_get_last_native_error(void);

/* -------------------------------------------------------------------------
 * JNI: MetalNativeBridge.metallumLastNativeError()
 *
 * 返回最近一次 native MSL 编译/入口点解析失败的错误文本。
 * 若无错误返回空字符串。
 */
JNIEXPORT jstring JNICALL
Java_com_metallum_client_metal_render_bridge_MetalNativeBridge_metallumLastNativeError(
    JNIEnv *env, jclass cls) {
    (void)cls;
    const char *err = metallum_get_last_native_error();
    if (err == NULL || err[0] == '\0') {
        return (*env)->NewStringUTF(env, "");
    }
    return (*env)->NewStringUTF(env, err);
}
