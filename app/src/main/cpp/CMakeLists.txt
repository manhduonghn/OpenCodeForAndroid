cmake_minimum_required(VERSION 3.22.1)
project(opencode_terminal LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# A pseudo-terminal for the native Terminal screen. It only needs libc, so every
# ABI of the app gets the same library.
add_library(opencode_pty SHARED pty.cpp)
target_link_options(opencode_pty PRIVATE "-Wl,-z,max-page-size=16384")
