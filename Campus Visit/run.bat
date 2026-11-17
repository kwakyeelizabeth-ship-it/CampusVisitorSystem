@echo off
setlocal
cd /d "%~dp0"
if not exist out mkdir out
echo Compiling Palm University College Visitor System...
javac --add-modules jdk.httpserver -encoding UTF-8 -d out src\main\java\VisitorSystem.java
if errorlevel 1 exit /b 1
echo.
echo Starting visitor system at http://localhost:8080
java --add-modules jdk.httpserver -cp out VisitorSystem
