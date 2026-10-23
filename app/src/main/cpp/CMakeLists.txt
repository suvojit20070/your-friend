cmake_minimum_required(VERSION 3.22.1)
project("agentnative")

add_library(agentnative SHARED audio_native.cpp)

find_library(log-lib log)
target_link_libraries(agentnative ${log-lib})

target_compile_options(agentnative PRIVATE -O3 -ffast-math)
