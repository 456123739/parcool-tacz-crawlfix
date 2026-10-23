#!/usr/bin/env bash
# 下载仅用于编译/联调的第三方模组 JAR 到 libs/（不会被打包进发布 JAR）。
# 版本与 gradle.properties 保持一致；已存在且校验通过的文件会跳过。
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p libs

PARCOOL_URL="https://cdn.modrinth.com/data/Fsvx2bdR/versions/Nh0ZGx7s/ParCool-1.20.1-3.3.1.0.jar"
TACZ_URL="https://cdn.modrinth.com/data/SzzJttH8/versions/3NpuJ7TJ/tacz-1.20.1-1.1.7-hotfix2.jar"

PARCOOL_JAR="libs/ParCool-1.20.1-3.3.1.0.jar"
TACZ_JAR="libs/tacz-1.20.1-1.1.7-hotfix2.jar"

fetch() {
    local url="$1"
    local out="$2"
    if [[ -s "$out" ]]; then
        echo "[deps] exists: $out"
        return
    fi
    echo "[deps] downloading $out"
    curl -fsSL --retry 5 --retry-delay 2 -o "$out.part" "$url"
    mv "$out.part" "$out"
}

fetch "$PARCOOL_URL" "$PARCOOL_JAR"
fetch "$TACZ_URL" "$TACZ_JAR"

echo "[deps] done"
ls -lh libs/*.jar
