cmake_minimum_required(VERSION 3.22.1)
project(zeroinput_rime LANGUAGES C CXX)

set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

if(NOT DEFINED ZEROINPUT_THIRD_PARTY_DIR)
  message(FATAL_ERROR "ZEROINPUT_THIRD_PARTY_DIR is required")
endif()

set(RIME_ROOT "${ZEROINPUT_THIRD_PARTY_DIR}/librime")
set(RIME_DEPS_ROOT "${ZEROINPUT_THIRD_PARTY_DIR}/rime-deps")
set(BOOST_ROOT "${ZEROINPUT_THIRD_PARTY_DIR}/boost")
if(NOT EXISTS "${RIME_ROOT}/CMakeLists.txt")
  message(FATAL_ERROR "Run tools/bootstrap-rime.ps1 before native builds")
endif()
if(NOT EXISTS "${BOOST_ROOT}/CMakeLists.txt")
  message(FATAL_ERROR "Boost source is missing; run tools/bootstrap-rime.ps1")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake" ${CMAKE_MODULE_PATH})

set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(BUILD_DATA OFF CACHE BOOL "" FORCE)
set(BUILD_SAMPLE OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ENABLE_LOGGING OFF CACHE BOOL "" FORCE)
set(ENABLE_TIMESTAMP OFF CACHE BOOL "" FORCE)
set(ENABLE_EXTERNAL_PLUGINS OFF CACHE BOOL "" FORCE)

set(BOOST_INCLUDE_LIBRARIES
  algorithm
  crc
  interprocess
  range
  regex
  scope_exit
  signals2
  unordered
  uuid
)
add_subdirectory("${BOOST_ROOT}" "${CMAKE_BINARY_DIR}/boost" EXCLUDE_FROM_ALL)

set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${RIME_DEPS_ROOT}/yaml-cpp" "${CMAKE_BINARY_DIR}/yaml-cpp" EXCLUDE_FROM_ALL)

set(LEVELDB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(LEVELDB_INSTALL OFF CACHE BOOL "" FORCE)
set(HAVE_SNAPPY OFF CACHE BOOL "" FORCE)
add_subdirectory("${RIME_DEPS_ROOT}/leveldb" "${CMAKE_BINARY_DIR}/leveldb" EXCLUDE_FROM_ALL)

add_subdirectory("${RIME_DEPS_ROOT}/marisa-trie" "${CMAKE_BINARY_DIR}/marisa" EXCLUDE_FROM_ALL)

set(ENABLE_GTEST OFF CACHE BOOL "" FORCE)
set(BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(ENABLE_DARTS OFF CACHE BOOL "" FORCE)
set(LIBMARISA marisa)
set(USE_SYSTEM_MARISA ON CACHE BOOL "" FORCE)
add_subdirectory("${RIME_DEPS_ROOT}/opencc" "${CMAKE_BINARY_DIR}/opencc" EXCLUDE_FROM_ALL)

set(ZEROINPUT_OPENCC_INCLUDE "${CMAKE_BINARY_DIR}/generated/include")
file(MAKE_DIRECTORY "${ZEROINPUT_OPENCC_INCLUDE}/opencc")
file(GLOB OPENCC_PUBLIC_HEADERS "${RIME_DEPS_ROOT}/opencc/src/*.hpp")
file(COPY ${OPENCC_PUBLIC_HEADERS} DESTINATION "${ZEROINPUT_OPENCC_INCLUDE}/opencc")
if(EXISTS "${CMAKE_BINARY_DIR}/opencc/src/opencc_config.h")
  file(COPY "${CMAKE_BINARY_DIR}/opencc/src/opencc_config.h"
       DESTINATION "${ZEROINPUT_OPENCC_INCLUDE}/opencc")
endif()

set(X11Keysym "${RIME_ROOT}/include")
add_subdirectory("${RIME_ROOT}" "${CMAKE_BINARY_DIR}/librime" EXCLUDE_FROM_ALL)
target_compile_options(rime-static PRIVATE -Wno-error=deprecated-declarations)

add_library(zeroinput_rime SHARED zero_rime_jni.cpp)
set(OCTAGRAM_ROOT "${RIME_DEPS_ROOT}/librime-octagram")
if(NOT EXISTS "${OCTAGRAM_ROOT}/src/octagram.cc")
  message(FATAL_ERROR "Pinned Octagram sources are required. Run tools/bootstrap-rime.ps1")
endif()
target_sources(rime-static PRIVATE
  "${CMAKE_CURRENT_LIST_DIR}/octagram_runtime.cpp"
  "${OCTAGRAM_ROOT}/src/gram_db.cc"
  "${OCTAGRAM_ROOT}/src/gram_encoding.cc"
  "${OCTAGRAM_ROOT}/src/grammar_module.cc"
  "${OCTAGRAM_ROOT}/src/octagram.cc"
)
target_include_directories(rime-static PRIVATE "${OCTAGRAM_ROOT}/src")
target_include_directories(zeroinput_rime PRIVATE
  "${OCTAGRAM_ROOT}/src"
  "${RIME_ROOT}/deps/darts-clone/include"
  "${RIME_ROOT}/deps/utf8cpp/source"
  "${ZEROINPUT_OPENCC_INCLUDE}"
  "${RIME_ROOT}/src"
  "${RIME_ROOT}/include"
  "${CMAKE_BINARY_DIR}/librime/src"
)
target_link_libraries(zeroinput_rime PRIVATE rime-static android log)
target_compile_options(zeroinput_rime PRIVATE
  -Wall
  -Wextra
  -Werror=return-type
  -fvisibility=hidden
)
target_link_options(zeroinput_rime PRIVATE "LINKER:--gc-sections")
