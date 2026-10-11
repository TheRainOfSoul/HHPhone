# HHPhone

SIP-звонилка для Android: HHPBX и любые SIP-АТС. Голос и видео, несколько учёток, TLS/SRTP.
Языки: русский, армянский, английский. Android 9+.
Учётки сотрудникам — по QR с блокировкой PIN (`docs/qr.md`).

Основан на [baresip+](https://github.com/juha-h/baresip-studio/tree/video) (TutPro Inc., BSD 3-Clause)
и библиотеке [baresip](https://github.com/baresip/baresip). Нативные библиотеки включают GPL-компоненты
(x264, x265, bcg729, ZRTPCPP), поэтому HHPhone распространяется под **GNU GPL v3** (текст — `COPYING`); исходная лицензия baresip-studio (BSD 3-Clause) — в `LICENSE`.

## Сборка

Нужны: Android SDK (NDK 30.0.16248370, CMake 3.31.6), JDK 21, `gh`.

    powershell -File scripts\fetch-native.ps1
    gradlew assembleHhDebug

Выпуск: `powershell -File scripts\release.ps1 -Notes "что нового"` (`-Prerelease` — тестовый выпуск, телефоны его не видят).
Ключ подписи — `%USERPROFILE%\.hhphone\keystore.properties`. Телефоны с 0.3.0 обновляются сами (GitHub Releases, раз в 6 часов);
0.2.0 и ниже — вручную.
Пересборка нативных библиотек — `docs/native.md`. Проверка — `docs/testing.md`.

## Обновление из upstream

    git fetch upstream
    git merge upstream/video
    python scripts/brand-strings.py
    python scripts/missing-strings.py   # перевести новые строки ru/hy

Наш код: flavor `app/src/hh/`, `HH*.kt`, `Colors.kt`, `BaresipApp.kt` (одна строка), `BaresipService.kt` (`setPackage(packageName)`),
`MainScreen.kt` (заголовок `app_name_plus`).
