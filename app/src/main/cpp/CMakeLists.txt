cmake_minimum_required(VERSION 3.22.1)
project(recorder24h_native LANGUAGES C CXX)

include(FetchContent)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# The installable development artifact is an Android debug APK, but whisper.cpp is
# production inference code. CMake's Debug configuration otherwise uses debug-oriented
# compiler flags, which can make local inference unusably slow. add_compile_options()
# is appended after per-configuration flags, so -O3 wins while debug symbols remain.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-O3)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Build both CPU and Vulkan backends into the same native library. Runtime selection is
# explicit in app settings; CPU remains available and is never selected as an automatic fallback.
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

# Android NDK r12+ ships a host glslc under shader-tools. ggml's Vulkan backend needs a host
# shader compiler while the actual native library is cross-compiled for arm64 Android.
if(ANDROID AND CMAKE_ANDROID_NDK)
    set(_android_glslc "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64/glslc")
    if(EXISTS "${_android_glslc}")
        set(Vulkan_GLSLC_EXECUTABLE "${_android_glslc}" CACHE FILEPATH "Android NDK glslc" FORCE)
    endif()
endif()

# whisper.cpp v1.9.1's Vulkan CMake explicitly requires a SPIRV-Headers CONFIG package even
# though it does not link the package target. Android NDK does not ship that CMake package.
# Fetch the Khronos header-only source at the revision used by SPIRV-Tools v2026.2 and expose a
# minimal build-tree package config. This keeps the native build self-contained and reproducible.
FetchContent_Declare(
        spirv_headers_source
        URL https://github.com/KhronosGroup/SPIRV-Headers/archive/ad9184e76a66b1001c29db9b0a3e87f646c64de0.tar.gz)
FetchContent_GetProperties(spirv_headers_source)
if(NOT spirv_headers_source_POPULATED)
    FetchContent_Populate(spirv_headers_source)
endif()
set(_spirv_headers_config_dir "${CMAKE_BINARY_DIR}/spirv-headers-config")
file(MAKE_DIRECTORY "${_spirv_headers_config_dir}")
file(WRITE "${_spirv_headers_config_dir}/SPIRV-HeadersConfig.cmake"
"if(NOT TARGET SPIRV-Headers::SPIRV-Headers)\n"
"  add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)\n"
"  set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES \"${spirv_headers_source_SOURCE_DIR}/include\")\n"
"endif()\n"
"set(SPIRV-Headers_FOUND TRUE)\n")
set("SPIRV-Headers_DIR" "${_spirv_headers_config_dir}" CACHE PATH "SPIRV-Headers build-tree config" FORCE)

FetchContent_Declare(
        whisper_cpp
        URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.1.tar.gz)
FetchContent_MakeAvailable(whisper_cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_features(whisper_jni PRIVATE cxx_std_17)
target_include_directories(whisper_jni PRIVATE
        ${whisper_cpp_SOURCE_DIR}/include
        ${whisper_cpp_SOURCE_DIR}/ggml/include)

find_library(log_lib log)
target_link_libraries(whisper_jni PRIVATE whisper ${log_lib})