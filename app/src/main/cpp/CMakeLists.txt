cmake_minimum_required(VERSION 3.22.1)
<<<<<<< HEAD
project(yourfriend LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libyourfriend.so: lip-sync audio analysis and the dynamic bridge to audio.cpp (OmniVoice).
add_library(yourfriend SHARED
        native_audio.cpp
        omnivoice_bridge.cpp)

target_compile_options(yourfriend PRIVATE -Wall -Wextra -O2 -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(yourfriend ${log-lib} dl)
=======
project("agentnative")

add_library(agentnative SHARED audio_native.cpp)

find_library(log-lib log)
target_link_libraries(agentnative ${log-lib})

target_compile_options(agentnative PRIVATE -O3 -ffast-math)
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
