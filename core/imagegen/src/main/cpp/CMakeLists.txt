cmake_minimum_required(VERSION 3.22.1)
project(ronie_imagegen LANGUAGES CXX)

set(PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/../../../prebuilt)
set(LIBDIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

if(NOT EXISTS ${LIBDIR}/libstable-diffusion.so)
    message(FATAL_ERROR "Missing ${LIBDIR}/libstable-diffusion.so - run scripts\\build-sd-libs.cmd first")
endif()

add_library(stable-diffusion SHARED IMPORTED)
set_target_properties(stable-diffusion PROPERTIES IMPORTED_LOCATION ${LIBDIR}/libstable-diffusion.so)

add_library(ronie_imagegen SHARED ronie_imagegen.cpp)
target_include_directories(ronie_imagegen PRIVATE ${PREBUILT}/include)
target_link_libraries(ronie_imagegen stable-diffusion log)
