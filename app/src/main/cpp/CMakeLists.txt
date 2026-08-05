cmake_minimum_required(VERSION 3.22.1)
project("igameboy")

# Set C++ Standard
set(CMAKE_CXX_STANDARD 17)

# FIX 1: Definisikan macro INLINE secara global agar compiler C/Clang
# tidak menganggap INLINE sebagai unknown type di file seperti sound.c
add_compile_definitions(INLINE=inline)

# Kumpulkan semua file C & C++ secara otomatis dari subfolder
file(GLOB_RECURSE LIBRETRO_COMMON_SOURCES
    "libretro-common/*.c"
)

file(GLOB_RECURSE CORE_SOURCES
    "src/*.c"
    "src/*.cpp"
)

# File JNI dan Libretro Wrapper utama
set(NATIVE_SOURCES
    native-lib.cpp
    libretro/libretro.c
)

# Buat shared library (.so)
add_library(
    igba_core
    SHARED
    ${NATIVE_SOURCES}
    ${CORE_SOURCES}
    ${LIBRETRO_COMMON_SOURCES}
)

# FIX 2: Perbaiki Include Directories menggunakan target_include_directories
# Ini memastikan folder libretro-common/include terdaftar dengan benar, 
# sekaligus menambahkan root folder agar `#include "libretro/libretro.h"` maupun `#include "libretro.h"` bisa ditemukan.
target_include_directories(igba_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libretro
    ${CMAKE_CURRENT_SOURCE_DIR}/libretro-common/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

# Cari Android System Libraries
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

# Link library
target_link_libraries(
    igba_core
    ${log-lib}
    ${jnigraphics-lib}
)
