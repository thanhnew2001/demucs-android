cmake_minimum_required(VERSION 3.10.2)

project(demucs_ndk CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Compiler options for high performance; customize according to your need
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -Ofast -fno-unsafe-math-optimizations -fassociative-math -freciprocal-math -fno-signed-zeros -DNDEBUG")

# Add compile options for ARMv7-a
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon")
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a")
endif()

# Include directories for all components
include_directories(${CMAKE_SOURCE_DIR}/eigen)
include_directories(${CMAKE_SOURCE_DIR}/libnyquist/include)
include_directories(${CMAKE_SOURCE_DIR}/demucs)
include_directories(${CMAKE_SOURCE_DIR}/resampler)

# Assuming `libnyquist` has its own CMakeLists.txt
set(LIBNYQUIST_BUILD_EXAMPLE OFF CACHE BOOL "Disable libnyquist example" FORCE)
add_subdirectory(${CMAKE_SOURCE_DIR}/libnyquist)

# Source file globbing
file(GLOB DEMUCS_SOURCES "${CMAKE_SOURCE_DIR}/demucs/*.cpp")
file(GLOB RESAMPLER_SOURCES "${CMAKE_SOURCE_DIR}/resampler/*.cpp")

# Create individual libraries
add_library(demucs_lib STATIC ${DEMUCS_SOURCES})
add_library(resampler_lib STATIC ${RESAMPLER_SOURCES})

# The `libnyquist` library is added via add_subdirectory and doesn't need an explicit add_library call here, unless it's structured differently

# Final demucs_ndk library that links everything together
add_library(demucs_ndk SHARED ${DEMUCS_SOURCES} ${RESAMPLER_SOURCES} demucs_ndk.cpp)

find_library(LOG_LIB log)

# Linking libraries together
target_link_libraries(demucs_ndk demucs_lib resampler_lib libnyquist ${LOG_LIB})
