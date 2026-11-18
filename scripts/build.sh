#!/usr/bin/env sh
# 全量构建：前端 Vite 构建 → 产物拷入单体 jar 的 static/ → 可执行 jar
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
mvn clean package
echo "构建完成：motorsizer-app/target/motorsizer-app-1.0.0-SNAPSHOT.jar"
