@echo off
setlocal
REM MotorSizer 免安装绿色包打包器（WebView2 原生窗口版）
REM 前置：scripts\build.bat 构建 jar；loader\build_loader.py 编译 loader
REM 环境：JAVA_HOME 指向 JDK 17 含 jmods

set PKG=%~dp0

if not exist "%JAVA_HOME%\bin\jpackage.exe" (
  if exist D:\Java\amazon-corretto-17\bin\jpackage.exe set JAVA_HOME=D:\Java\amazon-corretto-17
)
if not exist "%JAVA_HOME%\bin\jpackage.exe" (
  if exist D:\develop\jdk-17\bin\jpackage.exe set JAVA_HOME=D:\develop\jdk-17
)
if not exist "%JAVA_HOME%\bin\jpackage.exe" (
  echo [错误] 未找到 jpackage：%JAVA_HOME%
  exit /b 1
)

REM 探测 python
set PYTHON=
where python >nul 2>nul && set PYTHON=python
if not defined PYTHON if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set PYTHON=%LOCALAPPDATA%\Programs\Python\Python313\python.exe
if not defined PYTHON if exist C:\Users\Administrator\.workbuddy\binaries\python\versions\3.13.12\python.exe set PYTHON=C:\Users\Administrator\.workbuddy\binaries\python\versions\3.13.12\python.exe

if not defined PYTHON (
  echo [错误] 未找到 python，请安装 Python 3
  exit /b 1
)

echo 使用 JAVA_HOME=%JAVA_HOME%
echo 使用 PYTHON=%PYTHON%
"%PYTHON%" "%PKG%package_app.py"
endlocal
