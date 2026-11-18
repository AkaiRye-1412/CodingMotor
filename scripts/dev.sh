#!/usr/bin/env sh
# 开发模式：后端（跳过前端构建）+ 前端 Vite（/api 代理到 127.0.0.1:8080）
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)

(
  cd "$ROOT"
  mvn -q -pl motorsizer-app -am package -DskipTests -DskipFrontend=true
  java -jar motorsizer-app/target/motorsizer-app-1.0.0-SNAPSHOT.jar
) &

(
  cd "$ROOT/frontend"
  npm install
  npm run dev
) &

wait
