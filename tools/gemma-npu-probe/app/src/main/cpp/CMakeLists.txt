cmake_minimum_required(VERSION 3.22.1)
project(gemma_hexagon_probe LANGUAGES C)
add_library(gemma_hexagon_probe SHARED hexagon_probe.c)
target_link_libraries(gemma_hexagon_probe dl log)
