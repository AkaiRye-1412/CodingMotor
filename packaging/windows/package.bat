@echo off
setlocal
REM MotorSizer 免安装绿色包（R-3 裁定）：jdeps 分析 → jlink 裁剪 JRE → jpackage app-image
REM 前置：先跑 scripts\build.bat 得到 motorsizer-app\target\motorsizer-app-1.0.0-SNAPSHOT.jar
REM 环境：JAVA_HOME 指向含 jpackage/jlink/jmods 的 JDK 17

set ROOT=%~dp0..\..
set PKG=%~dp0
set JAR=%ROOT%\motorsizer-app\target\motorsizer-app-1.0.0-SNAPSHOT.jar
set APP_INPUT=%PKG%app-input
set RUNTIME=%PKG%jre
set DIST=%PKG%dist
set ANALYSIS=%PKG%deps-analysis

if not exist "%JAVA_HOME%\bin\jpackage.exe" (
  REM 未设或无效 JAVA_HOME 时探测本机常见 JDK 位置
  if exist D:\develop\jdk-17\bin\jpackage.exe set JAVA_HOME=D:\develop\jdk-17
)

if not exist "%JAVA_HOME%\bin\jpackage.exe" (
  echo [错误] 未找到 jpackage（需 JDK 17 + jmods）：%JAVA_HOME%
  exit /b 1
)

if not exist "%JAR%" (
  echo [错误] 未找到单体 jar：%JAR%
  echo 请先运行 scripts\build.bat 完成全量构建。
  exit /b 1
)

REM ---- 解包 fat jar 供 jdeps 分析 ----
if exist "%ANALYSIS%" rmdir /s /q "%ANALYSIS%"
mkdir "%ANALYSIS%"
pushd "%ANALYSIS%"
"%JAVA_HOME%\bin\jar.exe" -xf "%JAR%" BOOT-INF\classes BOOT-INF\lib

set DEPS=
for /f "usebackq delims=" %%M in (`"%JAVA_HOME%\bin\jdeps.exe" --print-module-deps --ignore-missing-deps --multi-release 17 -cp "BOOT-INF\lib\*" "BOOT-INF\classes" 2^>nul`) do set DEPS=%%M
popd
if "%DEPS%"=="" set DEPS=java.base
echo [1/3] jdeps 模块清单：%DEPS%

REM ---- 运行期动态依赖补充（反射/SPI 等 jdeps 看不到的） ----
REM jdk.localedata+jdk.charsets：中文环境；jdk.crypto.ec：加密套件；jdk.zipfs：嵌套 jar 读取
set MODULES=%DEPS%,jdk.localedata,jdk.charsets,jdk.crypto.ec,jdk.zipfs

REM ---- jlink ----
if exist "%RUNTIME%" rmdir /s /q "%RUNTIME%"
echo [2/3] jlink 裁剪 JRE...
"%JAVA_HOME%\bin\jlink.exe" --add-modules %MODULES% --include-locales=zh,en --strip-debug --no-header-files --no-man-pages --compress 2 --output "%RUNTIME%"
if errorlevel 1 exit /b 1

REM ---- jpackage ----
if exist "%DIST%" rmdir /s /q "%DIST%"
if not exist "%APP_INPUT%" mkdir "%APP_INPUT%"
copy /y "%JAR%" "%APP_INPUT%\" >nul

echo [3/3] jpackage app-image...
"%JAVA_HOME%\bin\jpackage.exe" --type app-image --name MotorSizer --input "%APP_INPUT%" --main-jar motorsizer-app-1.0.0-SNAPSHOT.jar --runtime-image "%RUNTIME%" --icon "%PKG%icons\motorsizer.ico" --dest "%DIST%" --app-version 1.0.0 --vendor Akribis --java-options "-Dfile.encoding=UTF-8" --java-options "-Dsun.stdout.encoding=UTF-8" --java-options "-Dsun.stderr.encoding=UTF-8"
if errorlevel 1 exit /b 1

echo.
echo 打包完成：%DIST%\MotorSizer\
echo 双击 %DIST%\MotorSizer\MotorSizer.exe 启动（首次启动自动建库、打开浏览器）。
endlocal
