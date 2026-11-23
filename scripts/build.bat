@echo off
setlocal
REM 全量构建：后端 jar → 前端 Vite 构建 → 前端产物并入 jar 的 static/ → 重打 jar
REM
REM 为什么不用 mvn 的 frontend-maven-plugin（npm ci）：
REM   node_modules 已存在时，npm ci 要先把整个目录删掉重装，会撞上 npm 的 safe-delete 闸门
REM   （同一轮批量删 200+ 文件需要人工确认），非交互下直接失败、构建中断；
REM   以前只能加 -DskipFrontend=true 绕过，结果 jar 里留着上一次的前端产物 ——
REM   版本号升了、界面和功能却还是旧的（1.1.0 → 1.1.3 踩过）。
REM 现在改成手动三步，复用 node_modules，构建结果确定。
set ROOT=%~dp0..
cd /d %ROOT%

REM 1) 后端（含单元测试），跳过 maven 里的前端插件
call mvn clean package -DskipFrontend=true
if errorlevel 1 exit /b 1

REM 2) 前端（复用已安装的 node_modules）
pushd frontend
call npm run build
if errorlevel 1 (popd & exit /b 1)
popd

REM 3) 前端产物落到 target/classes/static（与 pom 里 copy-frontend-dist 同一落点），再打一次 jar
if exist motorsizer-app\target\classes\static rmdir /s /q motorsizer-app\target\classes\static
xcopy /E /I /Y frontend\dist motorsizer-app\target\classes\static >nul
call mvn package -DskipTests -DskipFrontend=true
if errorlevel 1 exit /b 1

echo 构建完成：motorsizer-app\target\motorsizer-app-1.0.0-SNAPSHOT.jar
endlocal
