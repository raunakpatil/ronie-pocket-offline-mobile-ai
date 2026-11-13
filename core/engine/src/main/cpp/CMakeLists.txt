cmake_minimum_required(VERSION 3.22.1)
project(ronie_engine LANGUAGES CXX)

# llama.cpp + ggml are prebuilt by scripts\build-engine-libs.cmd:
#   libraries -> ../jniLibs/<abi>   (packaged into the APK automatically)
#   headers   -> ../../../prebuilt/include
set(PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/../../../prebuilt)
set(LIBDIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
if(NOT EXISTS ${LIBDIR}/libllama.so)
    message(FATAL_ERROR "Missing ${LIBDIR}/libllama.so - run scripts\\build-engine-libs.cmd first")
endif()

foreach(lib llama ggml ggml-base mtmd)
    add_library(${lib} SHARED IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${LIBDIR}/lib${lib}.so)
endforeach()

add_library(ronie_engine SHARED ronie_engine.cpp)
target_include_directories(ronie_engine PRIVATE ${PREBUILT}/include)
target_link_libraries(ronie_engine llama ggml ggml-base mtmd log)
