#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
chmod +x gradlew
./gradlew build --no-daemon
echo "Готово: build/libs/"; ls build/libs
