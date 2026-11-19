@echo off
setlocal
REM 开发模式：后端（跳过前端构建，禁用自动开浏览器——前端由 Vite 提供）+ 前端 Vite（/api 代理到 127.0.0.1:8080）
set ROOT=%~dp0..

start "MotorSizer 后端" cmd /k "cd /d %ROOT% && mvn -pl motorsizer-app -am package -DskipTests -DskipFrontend=true && java -Xms256m -Xmx768m -Dmotorsizer.openBrowser=false -jar motorsizer-app\target\motorsizer-app-1.0.0-SNAPSHOT.jar"
REM --host 127.0.0.1 不能省：Vite 默认绑 localhost，在本机会解析成 IPv6 的 ::1，
REM 结果是 http://127.0.0.1:5173 连不上（浏览器表现为「打不开」）。
start "MotorSizer 前端" cmd /k "cd /d %ROOT%\frontend && npm install && npm run dev -- --host 127.0.0.1"
REM 等 Vite 起来再开浏览器（首次 npm install 可能要一会儿，下面会轮询）
start "MotorSizer 浏览器" cmd /c "for /l %%i in (1,1,60) do (timeout /t 2 /nobreak >nul & curl -s -o nul http://127.0.0.1:5173/ && start http://127.0.0.1:5173 && exit)"

endlocal
