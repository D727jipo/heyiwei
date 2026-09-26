@echo off
rem ============================================================
rem  build.bat - build calc.exe with MSVC (x64)
rem  Usage: build.bat
rem ============================================================
setlocal enabledelayedexpansion
pushd "%~dp0"

set "VCVARS="
rem --- 1) common VS 2022/2026 BuildTools & Community locations ---
if not defined VCVARS if exist "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not defined VCVARS if exist "C:\Program Files\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=C:\Program Files\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not defined VCVARS if exist "C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not defined VCVARS if exist "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
if not defined VCVARS if exist "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat"
if not defined VCVARS if exist "C:\Program Files\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvars64.bat"
if not defined VCVARS if exist "C:\BuildTools\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=C:\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

rem --- 2) vswhere fallback ---
if not defined VCVARS (
  set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
  if exist "!VSWHERE!" (
    for /f "usebackq tokens=*" %%i in (`"!VSWHERE!" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
      if exist "%%i\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=%%i\VC\Auxiliary\Build\vcvars64.bat"
    )
  )
)

rem --- 3) MinGW g++ fallback ---
set "GXX="
where g++ >nul 2>nul && set "GXX=g++"

if not defined VCVARS if not defined GXX (
  echo [build] ERROR: no C++ compiler found ^(need MSVC vcvars64.bat or g++ in PATH^)
  popd
  exit /b 1
)

if not exist build mkdir build

if defined VCVARS (
  echo [build] using MSVC: "%VCVARS%"
  call "%VCVARS%" >nul
  if errorlevel 1 (
    echo [build] ERROR: vcvars64.bat failed
    popd
    exit /b 1
  )
  cl /nologo /std:c++17 /utf-8 /O2 /W4 /EHsc /MT /DNDEBUG /Fobuild\ /Fe:calc.exe src\bigdec.cpp src\scifunc.cpp src\main.cpp
  if errorlevel 1 (
    echo [build] ERROR: compilation failed
    popd
    exit /b 1
  )
) else (
  echo [build] using MinGW: %GXX%
  %GXX% -std=c++17 -O2 -Wall -Wextra -static -o calc.exe src\bigdec.cpp src\scifunc.cpp src\main.cpp
  if errorlevel 1 (
    echo [build] ERROR: compilation failed
    popd
    exit /b 1
  )
)

echo [build] OK -^> "%CD%\calc.exe"
dir /b calc.exe
popd
endlocal
