# Top-level CMake orchestrator for building the vendored SPIRV toolchain used
# by MetalUniversal (glslang + SPIRV-Cross, with SPIRV-Headers as a header-only
# dependency). The Gradle build currently drives each submodule's CMake directly
# (see build.gradle: buildIOSSpvc / buildMacSpvc / buildMacGlslang /
# buildIOSGlslang) for parity with the existing iOS SPIRV-Cross task; this file
# is kept as a reproducible single-shot entry point and as documentation of the
# exact option set required for a working MSL/C-API build.
#
# Produced shared libraries (macOS arm64 / iOS arm64), with CMake VERSION /
# SOVERSION symlinks resolved to the unversioned names at packaging time:
#   SPIRV-Headers ............ header-only INTERFACE library (no artifact)
#   glslang .................. libglslang.dylib
#                             (carries the glslang_* C API symbols; also embeds
#                              the MachineIndependent / GenericCodeGen /
#                              OSDependent / SPIRV sources at this commit)
#   glslang SPIRV target ..... libSPIRV.dylib
#                             (thin shared shim that links libglslang)
#   glslang resource limits .. libglslang-default-resource-limits.dylib
#   SPIRV-Cross .............. libspirv-cross-c-shared.dylib
#                             (carries spvc_* / CompilerMSL symbols)
#
# Note: at this commit GenericCodeGen, MachineIndependent and OSDependent are
# declared STATIC inside glslang and ship as .a archives, NOT .dylib; their
# real sources are compiled directly into libglslang.dylib. libOGLCompiler does
# not exist at this commit. The Gradle packaging step therefore only needs to
# copy the three .dylib files above for glslang symbol resolution at runtime.

# glslang at this commit declares cmake_minimum_required(VERSION 3.22.1); bump
# the top-level minimum to match so a single-shot configure of this file does
# not error when the glslang subdirectory is processed.
cmake_minimum_required(VERSION 3.22.1)
project(MetallumNative)

# 1. SPIRV-Headers (header-only INTERFACE library). Must come first so later
#    projects can see the SPIRV-Headers::SPIRV-Headers target if needed.
#    Option names verified against vendor/SPIRV-Headers/CMakeLists.txt at
#    commit 29981f6: SPIRV_HEADERS_ENABLE_TESTS / SPIRV_HEADERS_ENABLE_INSTALL
#    default to PROJECT_IS_TOP_LEVEL, so they are OFF when consumed as a
#    subdirectory — no extra flags required.
add_subdirectory(${CMAKE_SOURCE_DIR}/../vendor/SPIRV-Headers spirv_headers_build)

# 2. glslang. Option names verified against vendor/glslang/CMakeLists.txt at
#    commit 2eb8a58. IMPORTANT corrections vs. the original task spec:
#    - ENABLE_SPVREMAPPER does NOT exist at this commit (the option was
#      removed); it is omitted here.
#    - The install option is GLSLANG_ENABLE_INSTALL (NOT ENABLE_GLSLANG_INSTALL).
#    - ENABLE_OPT is forced OFF: glslang's External/spirv-tools submodule is
#      not checked out, and with ENABLE_OPT=ON glslang emits
#      SEND_ERROR "ENABLE_OPT set but SPIR-V tools not found". glslang uses its
#      own bundled SPIRV headers, so it does NOT need vendor/SPIRV-Headers nor
#      a SPIRV_HEADERS_SOURCE_DIR variable.
#    - GLSLANG_TESTS is forced OFF to avoid pulling in googletest.
set(ENABLE_GLSLANG_BINARIES OFF CACHE BOOL "" FORCE)
set(ENABLE_GLSLANG_JS OFF CACHE BOOL "" FORCE)
set(ENABLE_OPT OFF CACHE BOOL "" FORCE)
set(GLSLANG_TESTS OFF CACHE BOOL "" FORCE)
set(GLSLANG_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_SOURCE_DIR}/../vendor/glslang glslang_build)

# 3. SPIRV-Cross. Option names verified against vendor/SPIRV-Cross/CMakeLists.txt
#    at commit 6c09849. The shared C-API target is `spirv-cross-c-shared`,
#    producing libspirv-cross-c-shared.dylib (VERSION 0.68.0, SOVERSION 0).
set(SPIRV_CROSS_SHARED ON CACHE BOOL "" FORCE)
set(SPIRV_CROSS_STATIC OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_CLI OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_C_API ON CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_MSL ON CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_GLSL ON CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_HLSL OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_CPP OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_REFLECT OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_UTIL OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_SOURCE_DIR}/../vendor/SPIRV-Cross spirv_cross_build)
