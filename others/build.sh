#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"

mkdir -p .toolchain build
python3 tools/check_architecture.py
artifact() {
    local group=${1//./\/} name=$2 version=$3 filename="$2-$3.jar"
    local path=".toolchain/$filename"
    if [[ ! -s "$path" ]]; then
        printf 'Downloading %s\n' "$filename" >&2
        curl -fsSL --retry 2 --max-time 180 \
            "https://repo.maven.apache.org/maven2/$group/$name/$version/$filename" -o "$path.partial"
        mv "$path.partial" "$path"
    fi
    printf '%s' "$path"
}

# Kotlin 2.0 metadata is supported by the D8 shipped with Build-Tools 35.0.0.
KOTLIN=2.0.21
compiler=$(artifact org.jetbrains.kotlin kotlin-compiler-embeddable "$KOTLIN")
stdlib=$(artifact org.jetbrains.kotlin kotlin-stdlib "$KOTLIN")
script=$(artifact org.jetbrains.kotlin kotlin-script-runtime "$KOTLIN")
reflect=$(artifact org.jetbrains.kotlin kotlin-reflect 1.6.10)
daemon=$(artifact org.jetbrains.kotlin kotlin-daemon-embeddable "$KOTLIN")
trove=$(artifact org.jetbrains.intellij.deps trove4j 1.0.20200330)
coroutines=$(artifact org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.6.4)
annotations=$(artifact org.jetbrains annotations 24.1.0)
gson=$(artifact com.google.code.gson gson 2.13.2)
deps="$stdlib:$gson"
kotlinc() {
    java -Xmx768m -Dkotlin.colors.enabled=false -cp "$compiler:$stdlib:$script:$reflect:$daemon:$trove:$coroutines:$annotations" \
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 "$@"
}

if [[ ${1:-} == test ]]; then
    proxy_sources=(src/dev/t4xsuite/proxy/*.kt)
    android_proxy='src/dev/t4xsuite/proxy/AndroidCaVault.kt'
    filtered=()
    for source in "${proxy_sources[@]}"; do [[ "$source" == "$android_proxy" ]] || filtered+=("$source"); done
    kotlinc -classpath "$deps" -d build/tests.jar src/dev/t4xsuite/core/*.kt "${filtered[@]}" tests/*.kt
    java -cp "build/tests.jar:$deps" dev.t4xsuite.tests.CoreTestsKt
    java -cp "build/tests.jar:$deps" dev.t4xsuite.tests.ProxyTestsKt
    python3 tests/test_ui_contract.py
    exit
fi

SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}
TOOLS="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
if [[ ${PREFIX:-} == */com.termux/files/usr ]]; then
    AAPT2=${AAPT2:-$(command -v aapt2 || true)}  # The desktop SDK's binary is not native to Termux.
else
    AAPT2=${AAPT2:-$TOOLS/aapt2}
fi
for path in "$PLATFORM" "$TOOLS/lib/d8.jar" "$TOOLS/lib/apksigner.jar"; do
    if [[ ! -r "$path" ]]; then printf 'Missing SDK file: %s\n' "$path" >&2; exit 1; fi
done
if [[ ! -x "$AAPT2" ]]; then printf 'Install aapt2 or set AAPT2 to its full path.\n' >&2; exit 1; fi

WEBKIT_AAR=".toolchain/webkit-1.12.1.aar"
WEBKIT_JAR=".toolchain/webkit-1.12.1.jar"
if [[ ! -s "$WEBKIT_JAR" ]]; then
    curl -fsSL --retry 2 --max-time 120 \
        "https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.12.1/webkit-1.12.1.aar" -o "$WEBKIT_AAR.partial"
    mv "$WEBKIT_AAR.partial" "$WEBKIT_AAR"
    unzip -p "$WEBKIT_AAR" classes.jar > "$WEBKIT_JAR"
fi

"$AAPT2" compile --dir res -o build/resources.zip
"$AAPT2" link -I "$PLATFORM" --manifest AndroidManifest.xml -A assets \
    -o build/unsigned-res.apk build/resources.zip
kotlinc -classpath "$PLATFORM:$deps:$WEBKIT_JAR" -d build/app.jar src/dev/t4xsuite/{core,proxy,ui}/*.kt
java -Xmx768m -cp "$TOOLS/lib/d8.jar" com.android.tools.r8.D8 --min-api 29 \
    --lib "$PLATFORM" --output build/dex.zip build/app.jar "$stdlib" "$gson" "$WEBKIT_JAR"
python3 tools/package_apk.py build/unsigned-res.apk build/dex.zip build/unsigned.apk

KEYSTORE="$PWD/.toolchain/debug.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
    keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=T4X-SUITE Debug,O=Local Development,C=US'
fi
java -jar "$TOOLS/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass pass:android \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false \
    --v4-signing-enabled false --out build/t4x-suite-debug.apk build/unsigned.apk
java -jar "$TOOLS/lib/apksigner.jar" verify --verbose build/t4x-suite-debug.apk
printf '\nAPK: %s/build/t4x-suite-debug.apk\n' "$PWD"
