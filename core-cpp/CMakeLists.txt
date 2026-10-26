cmake_minimum_required(VERSION 3.16)
project(chemlab_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(MSVC)
    add_compile_options(/W3 /O2 /EHsc /utf-8)
    add_compile_definitions(_CRT_SECURE_NO_WARNINGS)
else()
    add_compile_options(-Wall -O2 -fvisibility=hidden)
endif()

# ---- 内核算法源码（不含 JNI / CLI，便于被两种宿主复用） ----
set(CORE_SOURCES
    src/mathutil.cpp
    src/stats.cpp
    src/regression.cpp
    src/chemistry.cpp
    src/signal.cpp
)

# ---- 共享库：chemlab_core ----
add_library(chemlab_core SHARED ${CORE_SOURCES})
target_include_directories(chemlab_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)

# ---- JNI 桥接库：chemlab_jni（依赖 JDK 头文件） ----
# 通过 -DJAVA_HOME=<jdk路径> 传入；未提供时自动跳过，保证内核可独立构建。
set(JAVA_HOME "" CACHE PATH "JDK 安装目录")
if(JAVA_HOME AND EXISTS "${JAVA_HOME}/include/jni.h")
    add_library(chemlab_jni SHARED jni/chemlab_jni.cpp ${CORE_SOURCES})
    target_include_directories(chemlab_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        "${JAVA_HOME}/include"
        "${JAVA_HOME}/include/win32"
    )
    target_compile_definitions(chemlab_jni PRIVATE CHEMLAB_JNI_BUILD)
    set_target_properties(chemlab_jni PROPERTIES OUTPUT_NAME "chemlab_jni")
    message(STATUS "JNI 桥接库已启用，JAVA_HOME=${JAVA_HOME}")
else()
    message(STATUS "未提供有效 JAVA_HOME，跳过 JNI 桥接库构建")
endif()

# ---- CLI：自检与脚本调用 ----
add_executable(chemlab_cli src/cli.cpp ${CORE_SOURCES})
target_include_directories(chemlab_cli PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

# 统一输出到 dist/native
set_target_properties(chemlab_core chemlab_cli PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../dist/native"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../dist/native"
    ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../dist/native"
)
if(TARGET chemlab_jni)
    set_target_properties(chemlab_jni PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../dist/native"
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../dist/native"
    )
endif()