cmake_minimum_required(VERSION 3.15)
project(heart_popup LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 输出可执行文件名为中文 "爱心弹窗.exe"
add_executable(爱心弹窗 WIN32 main.cpp)
target_link_libraries(爱心弹窗 PRIVATE user32 gdi32)

# Windows 下以 GUI 子系统运行 (无控制台窗口)
if(MSVC)
    set_target_properties(爱心弹窗 PROPERTIES LINK_FLAGS "/SUBSYSTEM:WINDOWS /ENTRY:wWinMainCRTStartup")
endif()
