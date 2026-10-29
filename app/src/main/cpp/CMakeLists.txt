cmake_minimum_required(VERSION 3.22.1)
project(yourfriend LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libyourfriend.so: native (C++) helpers for the audio hot loops: lip-sync, mouth shapes (visemes,
# the heaviest one), resampling, normalising and microphone level (RMS).
# dsp_core.* is plain C++ (testable on a PC); native_*.cpp are the JNI wrappers.
# Every function has a Java fallback, so the app still works if the library cannot load.
add_library(yourfriend SHARED
        native_audio.cpp
        native_dsp.cpp
        dsp_core.cpp)

target_compile_options(yourfriend PRIVATE -Wall -Wextra -O2 -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(yourfriend ${log-lib})
