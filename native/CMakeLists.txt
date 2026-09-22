cmake_minimum_required(VERSION 3.20)
project(hh-vm LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

add_library(cvm STATIC
  src/reader.cpp
  src/verify.cpp
  src/vm.cpp
  src/random.cpp
  src/disasm.cpp
  src/protocol.cpp)
target_include_directories(cvm PUBLIC src)
target_compile_options(cvm PRIVATE -Wall -Wextra -Wno-unused-parameter)

# One entity, one process: a small static binary so 1300 of them fit in memory.
add_executable(hh-vm src/main.cpp)
target_link_libraries(hh-vm PRIVATE cvm)
target_compile_options(hh-vm PRIVATE -Wall -Wextra)
if(MINGW)
  target_link_options(hh-vm PRIVATE -static -static-libgcc -static-libstdc++)
endif()

enable_testing()
add_executable(cvm-tests tests/tests.cpp)
target_link_libraries(cvm-tests PRIVATE cvm)
add_test(NAME cvm-tests COMMAND cvm-tests)
