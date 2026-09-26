@echo off
rem ============================================================
rem  build_apk.bat - Android 12 (Material You) variant
rem  Reuses the same verified core in ..\android\src\com\dsh\calc,
rem  only the UI/resources differ. Output: ..\calc-android12.apk
rem  (package com.dsh.calc12, installs side by side with the old one)
rem ============================================================
setlocal enabledelayedexpansion
pushd "%~dp0"

set "CORE=%~dp0..\android\src\com\dsh\calc"

set "SDK=E:\SDK"
if not exist "%SDK%\build-tools" if defined ANDROID_SDK_ROOT set "SDK=%ANDROID_SDK_ROOT%"
if not exist "%SDK%\build-tools" if defined ANDROID_HOME set "SDK=%ANDROID_HOME%"
if not exist "%SDK%\build-tools" (
  echo [apk12] ERROR: Android SDK not found. Set SDK=... at the top of this script.
  popd & exit /b 1
)

set "BT="
for /f "delims=" %%i in ('dir /b /ad /o-n "%SDK%\build-tools" 2^>nul') do (
  if not defined BT if exist "%SDK%\build-tools\%%i\aapt2.exe" set "BT=%SDK%\build-tools\%%i"
)
if not defined BT (
  echo [apk12] ERROR: no build-tools with aapt2.exe
  popd & exit /b 1
)

set "PLATFORM="
for /f "delims=" %%i in ('dir /b /ad /o-n "%SDK%\platforms" 2^>nul') do (
  if not defined PLATFORM if exist "%SDK%\platforms\%%i\android.jar" set "PLATFORM=%SDK%\platforms\%%i"
)
if not defined PLATFORM (
  echo [apk12] ERROR: no platforms/android-*\android.jar
  popd & exit /b 1
)
set "ANDROID_JAR=%PLATFORM%\android.jar"

if not defined JAVA_HOME (
  for %%v in (23 21 17 22 20 24 25 26 27 28) do (
    if not defined JAVA_HOME if exist "C:\Program Files\Java\jdk-%%v\bin\javac.exe" set "JAVA_HOME=C:\Program Files\Java\jdk-%%v"
  )
)
if not defined JAVA_HOME (
  for /d %%j in ("C:\Program Files\Java\jdk*") do (
    if not defined JAVA_HOME if exist "%%j\bin\javac.exe" set "JAVA_HOME=%%j"
  )
)
if not defined JAVA_HOME (
  echo [apk12] ERROR: JAVA_HOME not found.
  popd & exit /b 1
)

echo [apk12] build-tools = %BT%
echo [apk12] platform    = %PLATFORM%
echo [apk12] JAVA_HOME   = %JAVA_HOME%
echo.

echo [apk12] 1/8 clean
if exist build rmdir /s /q build
mkdir build
mkdir build\gen
mkdir build\classes
mkdir build\dex

echo [apk12] 2/8 aapt2 compile resources
"%BT%\aapt2.exe" compile --dir res -o build\res.zip
if errorlevel 1 goto :fail

echo [apk12] 3/8 aapt2 link (manifest + resources)
rem version code/name come from AndroidManifest.xml (single source of truth)
"%BT%\aapt2.exe" link -o build\app-unsigned.apk -I "%ANDROID_JAR%" ^
  --manifest AndroidManifest.xml --java build\gen ^
  --min-sdk-version 21 --target-sdk-version 34 ^
  build\res.zip
if errorlevel 1 goto :fail

echo [apk12] 4/8 javac (core from android + this UI)
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn ^
  -bootclasspath "%ANDROID_JAR%" -classpath "%ANDROID_JAR%" ^
  -d build\classes build\gen\com\dsh\calc12\R.java ^
  "%CORE%\BigDec.java" "%CORE%\Calc.java" "%CORE%\CalcException.java" "%CORE%\Limits.java" "%CORE%\SciFunc.java" ^
  src\com\dsh\calc12\MainActivity.java src\com\dsh\calc12\SciActivity.java
if errorlevel 1 (
  echo [apk12]    -source 1.8 not supported, retry with --release 11
  rmdir /s /q build\classes & mkdir build\classes
  "%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 --release 11 -nowarn ^
    -classpath "%ANDROID_JAR%" ^
    -d build\classes build\gen\com\dsh\calc12\R.java ^
    "%CORE%\BigDec.java" "%CORE%\Calc.java" "%CORE%\CalcException.java" "%CORE%\Limits.java" "%CORE%\SciFunc.java" ^
    src\com\dsh\calc12\MainActivity.java src\com\dsh\calc12\SciActivity.java
)
if errorlevel 1 goto :fail

echo [apk12] 5/8 jar + d8 (dex)
"%JAVA_HOME%\bin\jar.exe" cf build\classes.jar -C build\classes .
if errorlevel 1 goto :fail
call "%BT%\d8.bat" --release --min-api 21 --lib "%ANDROID_JAR%" --output build\dex build\classes.jar
if errorlevel 1 goto :fail

echo [apk12] 6/8 add classes.dex
python -c "import zipfile;z=zipfile.ZipFile(r'build\app-unsigned.apk','a',zipfile.ZIP_DEFLATED);z.write(r'build\dex\classes.dex','classes.dex');z.close()"
if errorlevel 1 goto :fail

echo [apk12] 7/8 zipalign
"%BT%\zipalign.exe" -f 4 build\app-unsigned.apk build\app-aligned.apk
if errorlevel 1 goto :fail

echo [apk12] 8/8 sign
if not exist build\debug.keystore (
  "%JAVA_HOME%\bin\keytool.exe" -genkeypair -v -keystore build\debug.keystore ^
    -alias androiddebugkey -storepass android -keypass android ^
    -keyalg RSA -keysize 2048 -validity 10000 ^
    -dname "CN=Android Debug,O=Android,C=US" >nul
  if errorlevel 1 goto :fail
)
call "%BT%\apksigner.bat" sign --ks build\debug.keystore --ks-pass pass:android ^
  --key-pass pass:android --v4-signing-enabled false ^
  --out "..\calc-android12.apk" build\app-aligned.apk
if errorlevel 1 goto :fail

call "%BT%\apksigner.bat" verify "..\calc-android12.apk"
if errorlevel 1 goto :fail

echo.
echo [apk12] OK -^> %~dp0..\calc-android12.apk
popd
endlocal
exit /b 0

:fail
echo.
echo [apk12] FAILED at the step above.
popd
endlocal
exit /b 1
