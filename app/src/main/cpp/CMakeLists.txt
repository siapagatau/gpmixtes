cmake_minimum_required(VERSION 3.22.1)
project(gpmix LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(gpmix SHARED
    mixer.cpp
    audio_io.cpp
    jni_bridge.cpp
    fx/fx_registry.cpp)

target_compile_features(gpmix PRIVATE cxx_std_17)
# Catatan: tanpa -ffast-math (merusak pemeriksaan NaN/Inf dan perilaku denormal yang kita andalkan).
target_compile_options(gpmix PRIVATE -O3 -Wall -Wno-unused-parameter)
target_link_options(gpmix PRIVATE -Wl,-z,max-page-size=16384)  # kompatibel perangkat 16 KB page
target_include_directories(gpmix PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(gpmix oboe::oboe log android)
