#!/bin/bash
# 独立构建脚本：不依赖 Gradle/Android Studio，只用 Android SDK build-tools + JDK
# 用法: ./build_apk.sh
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:-D:/soft/android-sdk}
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
KEYSTORE="../debug.keystore"   # 测试签名密钥（首次构建自动生成，不入库）

rm -rf build && mkdir -p build/gen build/classes

if [ ! -f "$KEYSTORE" ]; then
    echo "[0/6] 生成测试签名密钥"
    keytool -genkeypair -keystore "$KEYSTORE" -alias dkey -storepass android \
        -keypass android -dname "CN=Debug" -keyalg RSA -keysize 2048 -validity 10000
fi

echo "[1/6] aapt2 编译资源"
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "[2/6] aapt2 链接生成资源包 + R.java"
"$BT/aapt2.exe" link -o build/base.apk -I "$PLATFORM" \
    --manifest AndroidManifest.xml -A assets \
    --java build/gen build/res.zip

echo "[3/6] javac 编译"
javac -encoding UTF-8 -source 1.8 -target 1.8 \
    -bootclasspath "$PLATFORM" \
    -d build/classes \
    build/gen/com/example/bleprinter/R.java \
    src/com/example/bleprinter/*.java

echo "[4/6] d8 转 dex"
"$BT/d8.bat" --lib "$PLATFORM" --release --output build \
    $(find build/classes -name '*.class')

echo "[5/6] 合并 APK"
python - <<'EOF'
import zipfile, shutil
shutil.copy('build/base.apk', 'build/app-unsigned.apk')
with zipfile.ZipFile('build/app-unsigned.apk', 'a') as z:
    z.write('build/classes.dex', 'classes.dex')
print('merged')
EOF

echo "[6/6] 对齐 + 签名"
"$BT/zipalign.exe" -f 4 build/app-unsigned.apk build/app-aligned.apk
"$BT/apksigner.bat" sign --ks "$KEYSTORE" --ks-pass pass:android \
    --out BlePrinterDemo.apk build/app-aligned.apk

echo "完成: $(pwd)/BlePrinterDemo.apk"
