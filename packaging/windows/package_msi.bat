@echo off
setlocal
REM MotorSizer 安装包（.msi）打包器
REM 前置：scripts\build.bat 构建 jar；loader\build_loader.py 编译 loader；package_app.py 产 app-image
REM 环境：JAVA_HOME 指向 JDK 17；WiX 在 D:\WiX\3.14.1（或设 WIX 环境变量）

set PKG=%~dp0

REM 探测 python
set PYTHON=
where python >nul 2>nul && set PYTHON=python
if not defined PYTHON if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set PYTHON=%LOCALAPPDATA%\Programs\Python\Python313\python.exe
if not defined PYTHON if exist C:\Users\Administrator\.workbuddy\binaries\python\versions\3.13.12\python.exe set PYTHON=C:\Users\Administrator\.workbuddy\binaries\python\versions\3.13.12\python.exe

if not defined PYTHON (
  echo [错误] 未找到 python，请安装 Python 3
  exit /b 1
)

echo 使用 PYTHON=%PYTHON%
"%PYTHON%" "%PKG%build_msi.py"
endlocal
