cmake_minimum_required(VERSION 3.16)
project(ronie_probe CXX C)
set(CMAKE_CXX_STANDARD 17)

# Same llama.cpp source as the phone build (third_party/llama.cpp, commit d6be065).
# RONIE_ALL_TOOLS=ON (used on Modal) also builds llama-server and the patched llama-cvector-generator.
option(RONIE_ALL_TOOLS "also build llama.cpp tools" OFF)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS ${RONIE_ALL_TOOLS} CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER ${RONIE_ALL_TOOLS} CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ${RONIE_ALL_TOOLS} CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/llama.cpp llama)

add_executable(probe_dump probe_dump.cpp)
target_include_directories(probe_dump PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/llama.cpp/vendor)
target_link_libraries(probe_dump PRIVATE llama ggml)

# Persona state for a fast first launch (scripts\make-persona-state.cmd).
add_executable(persona_state ../persona_state/persona_state.cpp)
target_link_libraries(persona_state PRIVATE llama ggml)
