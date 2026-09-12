@echo off
rem Fallback launcher. Use this if double-clicking the exe shows
rem "Failed to launch JVM": this bat runs the bundled JRE directly
rem and prints the real error message in this console window.
cd /d %~dp0
if exist "runtime\bin\java.exe" goto ok
echo [ERROR] runtime\ folder not found next to this bat.
echo If you opened the zip directly: extract the WHOLE folder first, then run.
pause
exit /b 1
:ok
set JAR=
for %%f in ("app\*.jar") do set JAR=%%f
if not "%JAR%"=="" goto run
echo [ERROR] app\*.jar not found. The package is incomplete, please re-extract or reinstall.
pause
exit /b 1
:run
echo Starting... Keep this window open (closing it stops the app).
echo Browser will open at http://localhost:8080 automatically.
"runtime\bin\java.exe" -Dfile.encoding=UTF-8 -Xms64m -Xmx768m -jar "%JAR%"
echo.
echo App exited. If there is an error above, screenshot it and ask for support.
pause
