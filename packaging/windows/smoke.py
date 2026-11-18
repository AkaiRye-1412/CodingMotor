# -*- coding: utf-8 -*-
"""绿色包冒烟验证（R-3 关闭条件：无 JRE 干净环境可跑）。

步骤：
1. 启动 dist/MotorSizer/MotorSizer.exe（若已有实例在运行会因单实例锁直接退出，先清锁）
2. 轮询 %APPDATA%/MotorSizer/app.lock 拿实际端口
3. GET /api/health、GET /（首页）、GET /api/motor-templates
4. 输出结论；进程保持运行由人工检查界面，或 --kill 时结束后清理
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

DIST = os.path.join(os.path.dirname(__file__), "dist", "MotorSizer", "MotorSizer.exe")
LOCK = os.path.join(os.environ.get("APPDATA", ""), "MotorSizer", "app.port")


def read_lock_port():
    try:
        content = open(LOCK, encoding="utf-8").read().strip()
        return int(content) if content else -1
    except (OSError, ValueError):
        return -1


def http_get(url, timeout=5):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        body = response.read()
        return response.status, body


def main():
    kill = "--kill" in sys.argv
    if not os.path.exists(DIST):
        print("[错误] 未找到 exe：%s" % DIST)
        return 1
    if not kill:
        # DETACHED + 新进程组：让 exe 真正脱离父进程，避免冒烟脚本所在终端关闭时被连带结束
        creationflags = 0
        if os.name == "nt":
            creationflags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
        subprocess.Popen([DIST], cwd=os.path.dirname(DIST), creationflags=creationflags)
    deadline = time.time() + 60
    port = -1
    while time.time() < deadline:
        port = read_lock_port()
        if port > 0:
            break
        time.sleep(0.5)
    if port <= 0:
        print("[失败] 60s 内未在锁文件中看到端口，启动可能失败。查看 %s 日志。"
              % os.path.join(os.environ.get("APPDATA", ""), "MotorSizer", "logs"))
        return 1
    print("OK 端口: %d" % port)

    base = "http://127.0.0.1:%d" % port
    status, body = http_get(base + "/api/health")
    print("OK /api/health: %d %s" % (status, body.decode("utf-8", "replace")[:120]))
    assert json.loads(body)["data"]["status"] == "UP"

    status, body = http_get(base + "/")
    print("OK 首页: HTTP %d, %d bytes, %s" % (status, len(body), body[:60]))

    status, body = http_get(base + "/api/motor-templates")
    print("OK /api/motor-templates: HTTP %d, %d bytes" % (status, len(body)))

    if kill:
        # 只结束 MotorSizer.exe 进程树（jpackage 启动器自带子进程，/T 一并结束；
        # 不按 java.exe 通配清理，避免误杀本机其他 Java 程序）
        subprocess.run(["taskkill", "/IM", "MotorSizer.exe", "/T", "/F"],
                       capture_output=True)
        time.sleep(1)
        print("进程已结束，锁端口复位为:", read_lock_port())
    else:
        print("实例保持运行，界面应已自动在浏览器打开。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
