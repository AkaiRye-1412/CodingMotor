@echo off
setlocal
REM 开发模式：后端（跳过前端构建）+ 前端 Vite（/api 代理到 127.0.0.1:8080）
set ROOT=%~dp0..

start "MotorSizer 后端" cmd /k "cd /d %ROOT% && mvn -pl motorsizer-app -am package -DskipTests -DskipFrontend=true && java -jar motorsizer-app\target\motorsizer-app-1.0.0-SNAPSHOT.jar"
start "MotorSizer 前端" cmd /k "cd /d %ROOT%\frontend && npm install && npm run dev"

endlocal
