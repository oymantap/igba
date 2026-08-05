cmake_minimum_required(VERSION 3.22.1)
project("igameboy")

set(CMAKE_CXX_STANDARD 17)

# Definisikan macro INLINE & PTH
add_compile_definitions(
    INLINE=inline
    ANDROID
    HAVE_PTH
)

# Core Sources
file(GLOB_RECURSE CORE_SOURCES "src/*.c" "src/*.cpp")

# Common Sources
file(GLOB_RECURSE LIBRETRO_COMMON_SOURCES "libretro-common/*.c")

# EXCLUDE file xenon/sdl yang bikin build hancur!
list(FILTER LIBRETRO_COMMON_SOURCES EXCLUDE REGEX ".*xenon_sdl_threads\\.c$")
list(FILTER LIBRETRO_COMMON_SOURCES EXCLUDE REGEX ".*xenon_threads\\.c$")

set(NATIVE_SOURCES
    native-lib.cpp
    libretro/libretro.c
)

add_library(
    igba_core
    SHARED
    ${NATIVE_SOURCES}
    ${CORE_SOURCES}
    ${LIBRETRO_COMMON_SOURCES}
)

target_include_directories(igba_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libretro
    ${CMAKE_CURRENT_SOURCE_DIR}/libretro-common/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    igba_core
    ${log-lib}
    ${jnigraphics-lib}
)
