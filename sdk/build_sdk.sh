#!/bin/bash
# 构建 printer-sdk.jar：统一多平台打印机连接 SDK 的独立产物。
# 编译对 android.jar 进行（传输层含 Android 实现），核心类（VendorDB/LabelProtocols）
# 与 TCP 通道为平台无关纯 Java，普通 JVM 亦可加载使用（见 test/JvmSmokeTest.java）。
# 用法: ./build_sdk.sh
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:-D:/androidsdk}
PLATFORM="$SDK/platforms/android-35/android.jar"

rm -rf build && mkdir -p build/classes

echo "[1/2] javac 编译"
javac -encoding UTF-8 -source 1.8 -target 1.8 \
    -bootclasspath "$PLATFORM" \
    -d build/classes \
    src/com/dilabel/printer/*.java

echo "[2/2] 打包 jar"
jar cf build/printer-sdk.jar -C build/classes .

echo "完成: $(pwd)/build/printer-sdk.jar"
