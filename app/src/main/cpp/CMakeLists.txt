cmake_minimum_required(VERSION 3.22.1)
project("xpida_native")

add_library(xpida_native SHARED xpida_native.c)

target_link_libraries(xpida_native log)
