cmake_minimum_required(VERSION 3.22.1)
project("igameboy")

set(CMAKE_CXX_STANDARD 17)

# Wajib tambahkan LOAD_FROM_MEMORY agar retro_load_game menggunakan game->data
add_compile_definitions(
    INLINE=inline
    ANDROID
    HAVE_PTH
    LOAD_FROM_MEMORY
)

# Core Sources
file(GLOB_RECURSE CORE_SOURCES "src/*.c" "src/*.cpp")

# Common Sources
file(GLOB_RECURSE LIBRETRO_COMMON_SOURCES "libretro-common/*.c")

# EXCLUDE file xenon/sdl yang bikin build crash
list(FILTER LIBRETRO_COMMON_SOURCES EXCLUDE REGEX ".*xenon_sdl_threads\\.c$")
list(FILTER LIBRETRO_COMMON_SOURCES EXCLUDE REGEX ".*xenon_threads\\.c$")

set(NATIVE_SOURCES
    native-lib.cpp
    libretro/libretro.c
)

# Nama target library disesuaikan secara konsisten: igameboy
add_library(
    igameboy
    SHARED
    ${NATIVE_SOURCES}
    ${CORE_SOURCES}
    ${LIBRETRO_COMMON_SOURCES}
)

target_include_directories(igameboy PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libretro
    ${CMAKE_CURRENT_SOURCE_DIR}/libretro-common/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

# FIND LIBRARIES
find_library(log-lib log)
find_library(android-lib android) # <-- PENTING: Untuk ANativeWindow

target_link_libraries(
    igameboy
    ${log-lib}
    ${android-lib} # <-- PENTING: Didaftarin di sini
)
