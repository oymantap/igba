cmake_minimum_required(VERSION 3.22.1)
project("igameboy")

# Set C++ Standard
set(CMAKE_CXX_STANDARD 17)

# Include directories supaya header (.h) di semua subfolder terbaca
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libretro
    ${CMAKE_CURRENT_SOURCE_DIR}/libretro-common/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

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

# Cari Android System Libraries
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

# Link library
target_link_libraries(
    igba_core
    ${log-lib}
    ${jnigraphics-lib}
)
