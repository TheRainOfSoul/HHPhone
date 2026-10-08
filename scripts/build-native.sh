#!/bin/bash
# Сборка нативных библиотек HHPhone на Debian 13 (ВМ hhpbx-build).
# usage: build-native.sh <ветка-или-коммит libbaresip-android>
# Результат: /var/tmp/hhphone-native/distribution.video-<ver>.tar.gz + sources.txt (коммиты всех исходников).
set -euo pipefail
REF=${1:-video}
W=/var/tmp/hhphone-native
NDK_VER=30.0.16248370
SDK=/opt/Android
VER=82.1.0   # версия baresip+ в upstream app/build.gradle.kts (versionName)
mkdir -p "$W"
sudo apt-get install -y wget cmake make libtool m4 automake pkg-config git unzip openjdk-21-jdk-headless python3 nasm meson ninja-build
if [ ! -d "$SDK/ndk/$NDK_VER" ]; then
  sudo mkdir -p "$SDK" && sudo chown "$USER" "$SDK"
  cd "$W"
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -O clt.zip
  rm -rf "$SDK/cmdline-tools" && mkdir -p "$SDK/cmdline-tools" && unzip -q clt.zip -d "$SDK/cmdline-tools" && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest" && rm -f clt.zip
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "ndk;$NDK_VER"
fi
# Makefile ищет ndkVersion в /usr/src/baresip-studio/app/build.gradle.kts и кладёт результат в /usr/src/baresip-studio/distribution.video
sudo mkdir -p /usr/src/baresip-studio/app && sudo chown -R "$USER" /usr/src/baresip-studio
echo "    ndkVersion = \"$NDK_VER\"" > /usr/src/baresip-studio/app/build.gradle.kts
cd "$W"
rm -rf libbaresip-android
git clone https://github.com/juha-h/libbaresip-android.git
cd libbaresip-android && git checkout "$REF"
make download-sources
{ echo "libbaresip-android $(git rev-parse HEAD)"
  for d in */; do [ -d "$d/.git" ] && echo "${d%/} $(git -C "$d" rev-parse HEAD)"; done; } > "$W/sources.txt"
make all   # arm64-v8a + armeabi-v7a
make libbaresip ANDROID_TARGET_ARCH=x86_64   # для эмулятора
tar -C /usr/src/baresip-studio -czf "$W/distribution.video-$VER.tar.gz" distribution.video
ls -la "$W"/*.tar.gz "$W/sources.txt"
echo BUILD-NATIVE-DONE
