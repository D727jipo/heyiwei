@echo off
REM 一键构建脚本 (需要安装 Visual Studio 或 Build Tools, 且 cl.exe 在 PATH 中)
REM 用法: 在 "x64 Native Tools Command Prompt for VS" 中运行 build.bat

where cl >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 cl.exe, 请在 "x64 Native Tools Command Prompt for VS" 中运行本脚本。
    exit /b 1
)

cl /EHsc /O2 /std:c++17 /utf-8 main.cpp ^
   /link user32.lib gdi32.lib ^
   /SUBSYSTEM:WINDOWS /ENTRY:wWinMainCRTStartup ^
   /OUT:"爱心弹窗.exe"

if exist "爱心弹窗.exe" (
    echo.
    echo [成功] 已生成 爱心弹窗.exe
)
