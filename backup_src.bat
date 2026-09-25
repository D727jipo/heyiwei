@echo off
rem ============================================================
rem  backup_src.bat - keep a SEPARATE copy of the source tree
rem
rem  The copy goes to .\source-backup\ which is listed in
rem  .gitignore, so it is never committed (by requirement).
rem  The working tree itself is committed / pushed as usual.
rem
rem  Usage: backup_src.bat
rem ============================================================
setlocal
set "SRC=%~dp0"
if "%SRC:~-1%"=="\" set "SRC=%SRC:~0,-1%"
set "DST=%SRC%\source-backup"
if not exist "%DST%" mkdir "%DST%"

robocopy "%SRC%" "%DST%" /E /XD .git source-backup build out __pycache__ /XF *.obj *.idsig /NFL /NDL /NJH /NJS /NP
if errorlevel 8 (
  echo [backup] FAILED - robocopy errorlevel %errorlevel%
  exit /b 1
)

set "COMMIT=unknown"
for /f "delims=" %%i in ('git -C "%SRC%" rev-parse --short HEAD 2^>nul') do set "COMMIT=%%i"
>>"%DST%\backup-log.txt" echo %DATE% %TIME%  commit=%COMMIT%

echo [backup] source copied to "%DST%"
echo [backup] log: %DST%\backup-log.txt
exit /b 0
