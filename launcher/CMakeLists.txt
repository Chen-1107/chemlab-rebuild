# 启动器构建：产出项目根目录下的 "ChemLab Analyzer.exe"。
#
# 独立于 core-cpp 的 CMake 工程，避免把桌面启动器混进数值内核。
# 仅依赖 Windows SDK（user32），无第三方依赖。
#
# 调试：-DCHEMLAB_LAUNCHER_CONSOLE=ON 时构建为控制台程序（入口 wmain），
# 便于在终端查看启动器内部输出；此时 exe 留在构建目录，不覆盖正式产物。
cmake_minimum_required(VERSION 3.15)
project(chemlab_launcher C)

option(CHEMLAB_LAUNCHER_CONSOLE "Build launcher as a console app for debugging" OFF)

if (CHEMLAB_LAUNCHER_CONSOLE)
    add_executable(chemlab_launcher launcher.c)
    target_compile_definitions(chemlab_launcher PRIVATE CHEMLAB_CONSOLE_DEBUG)
else()
    add_executable(chemlab_launcher WIN32 launcher.c)
    # 输出到项目根目录，双击即可启动
    set_target_properties(chemlab_launcher PROPERTIES
        OUTPUT_NAME "ChemLab Analyzer"
        RUNTIME_OUTPUT_DIRECTORY          "${CMAKE_CURRENT_SOURCE_DIR}/.."
        RUNTIME_OUTPUT_DIRECTORY_DEBUG    "${CMAKE_CURRENT_SOURCE_DIR}/.."
        RUNTIME_OUTPUT_DIRECTORY_RELEASE  "${CMAKE_CURRENT_SOURCE_DIR}/.."
        RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_CURRENT_SOURCE_DIR}/.."
        RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL     "${CMAKE_CURRENT_SOURCE_DIR}/..")
endif()

target_link_libraries(chemlab_launcher PRIVATE user32)

# 源码含中文注释与中文字符串，显式按 UTF-8 解析（否则 MSVC 按本地代码页读会拆坏字面量）
if (MSVC)
    target_compile_options(chemlab_launcher PRIVATE /utf-8)
endif()