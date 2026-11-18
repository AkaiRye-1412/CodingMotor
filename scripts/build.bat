@echo off
setlocal
REM 全量构建：前端 Vite 构建 → 产物拷入单体 jar 的 static/ → 可执行 jar
set ROOT=%~dp0..
cd /d %ROOT%
call mvn clean package
if errorlevel 1 exit /b 1
echo 构建完成：motorsizer-app\target\motorsizer-app-1.0.0-SNAPSHOT.jar
endlocal
