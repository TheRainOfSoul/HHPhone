# Нативные библиотеки

APK линкуется с библиотеками из `distribution.video/` (не в git). Получить готовые:

    powershell -File scripts\fetch-native.ps1

Пересобрать (при обновлении baresip из upstream):
1. Включить ВМ hhpbx-build (192.168.109.131, Debian 13, пользователь hayk). Нужно ~10 ГБ свободного места на `/`.
2. `scp scripts/build-native.sh hayk@192.168.109.131:/var/tmp/` и запустить `/var/tmp/build-native.sh <коммит libbaresip-android ветки video>` (в фоне, лог в /var/tmp/build-native.log).
3. Опубликовать `distribution.video-<ver>.tar.gz` и `sources.txt` релизом `native-<ver>`, поднять версию по умолчанию в `scripts/fetch-native.ps1`.

Версия NDK в скрипте должна совпадать с `ndkVersion` в `app/build.gradle.kts`.
Сборка включает GPL-компоненты (x264, x265, bcg729, ZRTPCPP) — поэтому HHPhone распространяется под GPL.
