cmake_minimum_required(VERSION 3.22)
project(ZeroInputOnnxJni LANGUAGES C)
get_filename_component(ORT_BUILD "${ORT_LIBRARY}" DIRECTORY)
file(GLOB sources "${ORT_SOURCE}/java/src/main/native/*.c")
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION "${ORT_LIBRARY}")
add_library(onnxruntime4j_jni SHARED ${sources})
set_target_properties(onnxruntime4j_jni PROPERTIES C_STANDARD 11)
target_include_directories(onnxruntime4j_jni PRIVATE
    "${ORT_SOURCE}/include" "${ORT_SOURCE}/include/onnxruntime/core/session"
    "${ORT_SOURCE}/orttraining/orttraining/training_api/include"
    "${ORT_HEADERS}" "${ORT_BUILD}")
target_compile_options(onnxruntime4j_jni PRIVATE -fvisibility=hidden)
target_link_options(onnxruntime4j_jni PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
target_link_libraries(onnxruntime4j_jni PRIVATE onnxruntime log dl)
