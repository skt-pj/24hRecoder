cmake_minimum_required(VERSION 3.22.1)
project(recorder24h_native LANGUAGES C CXX)

include(FetchContent)

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

FetchContent_Declare(
        whisper_cpp
        URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.1.tar.gz)
FetchContent_MakeAvailable(whisper_cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_features(whisper_jni PRIVATE cxx_std_17)
target_include_directories(whisper_jni PRIVATE ${whisper_cpp_SOURCE_DIR}/include)

find_library(log_lib log)
target_link_libraries(whisper_jni PRIVATE whisper ${log_lib})
