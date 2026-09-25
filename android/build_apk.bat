@echo off
rem ============================================================
rem  build_apk.bat - build the Android APK without Gradle
rem  Needs: Android SDK (aapt2, d8, zipalign, apksigner) + JDK
rem  Usage: build_apk.bat
rem ============================================================
setlocal enabledelayedexpansion
pushd "%~dp0"

set "SDK=E:\SDK"
if not exist "%SDK%\build-tools" if defined ANDROID_SDK_ROOT set "SDK=%ANDROID_SDK_ROOT%"
if not exist "%SDK%\build-tools" if defined ANDROID_HOME set "SDK=%ANDROID_HOME%"
if not exist "%SDK%\build-tools" (
  echo [apk] ERROR: Android SDK not found. Set SDK=... at the top of this script.
  popd & exit /b 1
)

rem pick the newest build-tools
set "BT="
for /f "delims=" %%i in ('dir /b /ad /o-n "%SDK%\build-tools" 2^>nul') do (
  if not defined BT if exist "%SDK%\build-tools\%%i\aapt2.exe" set "BT=%SDK%\build-tools\%%i"
)
if not defined BT (
  echo [apk] ERROR: no build-tools with aapt2.exe under "%SDK%\build-tools"
  popd & exit /b 1
)

rem newest platform
set "PLATFORM="
for /f "delims=" %%i in ('dir /b /ad /o-n "%SDK%\platforms" 2^>nul') do (
  if not defined PLATFORM if exist "%SDK%\platforms\%%i\android.jar" set "PLATFORM=%SDK%\platforms\%%i"
)
if not defined PLATFORM (
  echo [apk] ERROR: no platforms/android-*\android.jar under "%SDK%\platforms"
  popd & exit /b 1
)
set "ANDROID_JAR=%PLATFORM%\android.jar"

rem JDK
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
  echo [apk] ERROR: JAVA_HOME not found. Install a JDK or set JAVA_HOME.
  popd & exit /b 1
)

echo [apk] SDK      = %SDK%
echo [apk] build-tools = %BT%
echo [apk] platform = %PLATFORM%
echo [apk] JAVA_HOME= %JAVA_HOME%
echo.

echo [apk] 1/8 clean
if exist build rmdir /s /q build
mkdir build
mkdir build\gen
mkdir build\classes
mkdir build\dex

echo [apk] 2/8 aapt2 compile resources
"%BT%\aapt2.exe" compile --dir res -o build\res.zip
if errorlevel 1 goto :fail

echo [apk] 3/8 aapt2 link (manifest + resources)
"%BT%\aapt2.exe" link -o build\app-unsigned.apk -I "%ANDROID_JAR%" ^
  --manifest AndroidManifest.xml --java build\gen ^
  --min-sdk-version 21 --target-sdk-version 34 --version-code 1 --version-name 1.0 ^
  build\res.zip
if errorlevel 1 goto :fail

echo [apk] 4/8 javac
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn ^
  -bootclasspath "%ANDROID_JAR%" -classpath "%ANDROID_JAR%" ^
  -d build\classes build\gen\com\dsh\calc\R.java src\com\dsh\calc\*.java
if errorlevel 1 (
  echo [apk]    -source 1.8 not supported by this JDK, retry with 11
  rmdir /s /q build\classes & mkdir build\classes
  "%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -source 11 -target 11 -nowarn ^
    -bootclasspath "%ANDROID_JAR%" -classpath "%ANDROID_JAR%" ^
    -d build\classes build\gen\com\dsh\calc\R.java src\com\dsh\calc\*.java
)
if errorlevel 1 goto :fail

echo [apk] 5/8 jar + d8 (dex)
"%JAVA_HOME%\bin\jar.exe" cf build\classes.jar -C build\classes .
if errorlevel 1 goto :fail
call "%BT%\d8.bat" --release --min-api 21 --lib "%ANDROID_JAR%" --output build\dex build\classes.jar
if errorlevel 1 goto :fail

echo [apk] 6/8 add classes.dex into apk
python -c "import zipfile;z=zipfile.ZipFile(r'build\app-unsigned.apk','a',zipfile.ZIP_DEFLATED);z.write(r'build\dex\classes.dex','classes.dex');z.close()"
if errorlevel 1 goto :fail

echo [apk] 7/8 zipalign
"%BT%\zipalign.exe" -f 4 build\app-unsigned.apk build\app-aligned.apk
if errorlevel 1 goto :fail

echo [apk] 8/8 sign
if not exist build\debug.keystore (
  "%JAVA_HOME%\bin\keytool.exe" -genkeypair -v -keystore build\debug.keystore ^
    -alias androiddebugkey -storepass android -keypass android ^
    -keyalg RSA -keysize 2048 -validity 10000 ^
    -dname "CN=Android Debug,O=Android,C=US" >nul
  if errorlevel 1 goto :fail
)
call "%BT%\apksigner.bat" sign --ks build\debug.keystore --ks-pass pass:android ^
  --key-pass pass:android --out "..\calc-android.apk" build\app-aligned.apk
if errorlevel 1 goto :fail

call "%BT%\apksigner.bat" verify --print-certs "..\calc-android.apk"
if errorlevel 1 goto :fail

echo.
echo [apk] OK -^> %~dp0..\calc-android.apk
"%BT%\aapt2.exe" dump badging "..\calc-android.apk" | findstr /b "package application-label launchable-activity sdkVersion targetSdkVersion"
popd
endlocal
exit /b 0

:fail
echo.
echo [apk] FAILED at the step above.
popd
endlocal
exit /b 1
