# HHPhone план 1 — форк и HH-оформление (v0.1.0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Рабочий APK HHPhone v0.1.0 — baresip+ под брендом HH (цвета HHDesign, иконка, ru/hy/en), ставится рядом с baresip+, учётки вводятся вручную, звонит голосом и видео через HHPBX.

**Architecture:** Форк ветки `video` из baresip-studio. Все HH-ресурсы лежат в отдельном product flavor `hh` (`app/src/hh/res`), который переопределяет ресурсы `main`, не трогая файлы upstream. Kotlin-код upstream правится точечно (`Colors.kt`, `BaresipApp.kt`), наш код — в `HH*.kt`. Нативные библиотеки собираются один раз на ВМ hhpbx-build и хранятся как ассет GitHub-релиза `native-82.1.0`, скрипт скачивает их в `distribution.video/`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Gradle 9.8 (Kotlin DSL), AGP, NDK 30.0.16248370, CMake 3.31.6, JDK 21, libbaresip-android (ветка video), Python 3 (скрипт строк), Node (иконки HHDesign).

**Spec:** `docs/superpowers/specs/2026-10-08-hhphone-design.md`

## Global Constraints

- `applicationId` = `am.dgsolutions.hhphone`; Kotlin-пакет и `namespace` остаются `com.tutpro.baresip.plus` (JNI-имена и слияния с upstream).
- Ветка `main` растёт от `upstream/video` (`https://github.com/juha-h/baresip-studio`); в `upstream` не пушить (push url = `no_push`).
- Наш код — в новых файлах `HH*.kt` и в `app/src/hh/`; в файлах upstream только точечные правки.
- Языки ровно ru, hy, en. Если системный язык не из них — русский (на Android 13+; см. Task 2).
- Цвета HHDesign: светлая — бордо `#7A0C2A` цвет действия; тёмная — заливки `#9E1B3A`, акценты `#E0607A`; ошибки — свой красный; кнопки «Позвонить» зелёная / «Сбросить» красная не меняются.
- Шрифт Noto Sans — системный, не вшивается.
- Лицензия GPL, код открыт; «О программе» упоминает baresip, TutPro Inc. (BSD-3) и GPL-компоненты.
- Подпись: `%USERPROFILE%\.hhphone\android-release.jks` + `%USERPROFILE%\.hhphone\keystore.properties`, вне git.
- minSdk 28, targetSdk 36, compileSdk 37 — как в upstream.
- Весь пользовательский текст на ru/hy/en; коммиты и документация — по-русски.

## Отклонения от спеки (уточнения при планировании)

1. Нативные библиотеки — не в git, а ассетом релиза `native-82.1.0` (сотни МБ `.a`/`.so` раздули бы репозиторий; upstream их тоже игнорирует). Скрипт `scripts/fetch-native.ps1` скачивает их.
2. Чужие переводы не удаляются из `res/`, а отсекаются `localeFilters` в flavor `hh` — ноль конфликтов при слияниях, результат в APK тот же.
3. Русский по умолчанию для чужого системного языка — только Android 13+ (per-app locale API). На Android 9–12 при немецком/китайском системном языке будет английский (`ponytail:`-комментарий в коде).

## Review Focus

1. Сборка после `git merge upstream/video`: новые строки upstream с «baresip» — `scripts/brand-strings.py` перегенерирует переопределения; новые непереведённые строки ловит lint `MissingTranslation` (Task 4, шаг проверки).
2. Системный язык не ru/hy/en на Android 13+ → приложение на русском, а не на английском (Task 2, тест `hhFallbackLanguage`).
3. Пользователь сам выбрал язык в настройках Android → наш фолбэк его не перетирает (Task 2, тест для непустого выбора).
4. Строки с URL (`github.com/baresip/baresip`, `juha-h/baresip-studio`) не должны ломаться при ребрендинге (Task 3, самопроверка скрипта).
5. HHPhone и baresip+ установлены одновременно → оба работают, не конфликтуют по `authorities` провайдеров и не получают события друг друга (Task 2 Step 3a + Step 5: установка рядом, звонок в HHPhone не будит baresip+).

---

### Task 1: Нативные библиотеки и первая сборка upstream

**Files:**
- Create: `scripts/fetch-native.ps1`
- Create: `scripts/build-native.sh` (запускается на ВМ)
- Create: `docs/native.md`
- Modify: `.gitignore` (снять игнор `gradle/wrapper/gradle-wrapper.jar`)
- Create: `gradle/wrapper/gradle-wrapper.jar`

**Interfaces:**
- Produces: каталог `distribution.video/` в корне репозитория (структура как ждёт `app/src/main/cpp/CMakeLists.txt`: `<lib>/lib/<abi>/*.a|*.so`, `<lib>/include`), ABI `arm64-v8a`, `armeabi-v7a`, `x86_64`; GitHub-релиз `native-82.1.0` с ассетом `distribution.video-82.1.0.tar.gz`.

- [ ] **Step 1: Запустить ВМ hhpbx-build и проверить ресурсы**

```bash
"/c/Program Files (x86)/VMware/VMware Workstation/vmrun.exe" -T ws start "F:\Wondershare\Debian 12.x 64-bit.vmx" nogui
ssh hayk@192.168.109.131 'sudo date -u -s "'"$(date -u '+%Y-%m-%d %H:%M:%S')"'"; cat /etc/debian_version; df -h /var/tmp; nproc; free -g'
```
Expected: Debian 13.x, на `/var/tmp` свободно ≥ 25 ГБ. Если меньше — остановиться и сообщить пользователю. Работать только в `/var/tmp` (`/tmp` — tmpfs в RAM).

- [ ] **Step 2: Написать `scripts/build-native.sh`**

```bash
#!/bin/bash
# Сборка нативных библиотек HHPhone на Debian 13 (ВМ hhpbx-build).
# usage: build-native.sh <ветка-или-коммит libbaresip-android>
# Результат: /var/tmp/hhphone-native/distribution.video-<ver>.tar.gz + sources.txt (коммиты всех исходников).
set -euo pipefail
REF=${1:-video}
W=/var/tmp/hhphone-native
NDK_VER=30.0.16248370
SDK=/opt/Android
mkdir -p "$W"
sudo apt-get install -y wget cmake make libtool m4 automake pkg-config git unzip openjdk-21-jdk-headless python3 nasm meson ninja-build
if [ ! -d "$SDK/ndk/$NDK_VER" ]; then
  sudo mkdir -p "$SDK" && sudo chown "$USER" "$SDK"
  cd "$W"
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -O clt.zip
  rm -rf "$SDK/cmdline-tools" && mkdir -p "$SDK/cmdline-tools" && unzip -q clt.zip -d "$SDK/cmdline-tools" && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null
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
VER=82.1.0   # версия baresip+ в upstream app/build.gradle.kts (versionName)
tar -C /usr/src/baresip-studio -czf "$W/distribution.video-$VER.tar.gz" distribution.video
ls -la "$W"/*.tar.gz "$W/sources.txt"
```

Примечание для исполнителя: если `apt-get` не находит пакет из списка — убрать его из списка (это подстраховка для ffmpeg-android-maker), не добавлять другие. Если сборка падает на отсутствующем инструменте — поставить именно его через apt и дописать в список в скрипте.

- [ ] **Step 3: Скопировать и запустить сборку в фоне**

```bash
scp scripts/build-native.sh hayk@192.168.109.131:/var/tmp/build-native.sh
ssh hayk@192.168.109.131 'chmod +x /var/tmp/build-native.sh && nohup /var/tmp/build-native.sh 333bd2fda692cf2f183e9c00500f13c635708dac > /var/tmp/build-native.log 2>&1 &'
```
`333bd2f…` — головной коммит ветки `video` libbaresip-android на 2026-10-08 (соответствует baresip+ 82.1.0). Ждать завершения через Monitor (`tail` лога до строки с `.tar.gz` или `Error`). Сборка ffmpeg/x265 на 4 ядрах занимает десятки минут.
Expected: в конце лога `distribution.video-82.1.0.tar.gz` и `sources.txt`.

- [ ] **Step 4: Забрать архив, опубликовать релиз `native-82.1.0`**

```bash
scp hayk@192.168.109.131:/var/tmp/hhphone-native/distribution.video-82.1.0.tar.gz hayk@192.168.109.131:/var/tmp/hhphone-native/sources.txt "$SCRATCH/"
gh release create native-82.1.0 "$SCRATCH/distribution.video-82.1.0.tar.gz" "$SCRATCH/sources.txt" \
  --repo TheRainOfSoul/HHPhone --title "Нативные библиотеки 82.1.0" --prerelease \
  --notes "Собрано scripts/build-native.sh из libbaresip-android (ветка video, коммит 333bd2f). Коммиты исходников — sources.txt. Не для установки: используется scripts/fetch-native.ps1."
```
(`$SCRATCH` — scratchpad сессии.) Релиз создаётся на текущем `main` — это нормально, тег только для ассетов.

- [ ] **Step 5: Написать `scripts/fetch-native.ps1`**

```powershell
# Скачивает нативные библиотеки baresip (ассет релиза native-<ver>) в distribution.video\.
# usage: powershell -File scripts\fetch-native.ps1 [-Version 82.1.0]
param([string]$Version = '82.1.0')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dest = Join-Path $root 'distribution.video'
if (Test-Path (Join-Path $dest 'baresip\lib\arm64-v8a\libbaresip.a')) { Write-Host "distribution.video уже есть"; exit 0 }
$tmp = Join-Path $env:TEMP "hhphone-native-$Version"
New-Item -ItemType Directory -Force $tmp | Out-Null
gh release download "native-$Version" --repo TheRainOfSoul/HHPhone --pattern "distribution.video-$Version.tar.gz" --dir $tmp --clobber
if ($LASTEXITCODE -ne 0) { throw "gh release download завершился с кодом $LASTEXITCODE" }
& "$env:SystemRoot\System32\tar.exe" -xzf (Join-Path $tmp "distribution.video-$Version.tar.gz") -C $root
if ($LASTEXITCODE -ne 0) { throw "tar завершился с кодом $LASTEXITCODE" }
Write-Host "Готово: $dest"
```
(Именно `System32\tar.exe`: tar из Git Bash ломает пути Windows.)

- [ ] **Step 6: Gradle wrapper jar, локальные NDK/CMake, первая сборка upstream**

`.gitignore` upstream игнорирует `gradle/wrapper/gradle-wrapper.jar` — удалить эту строку и положить jar:
```bash
cd /g/HHPhone && sed -i '/gradle\/wrapper\/gradle-wrapper.jar/d' .gitignore
cp /g/HHTvCam/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
"$LOCALAPPDATA/Android/Sdk/cmdline-tools/latest/bin/sdkmanager.bat" "ndk;30.0.16248370" "cmake;3.31.6" "platforms;android-37"
echo "sdk.dir=C\\:\\\\Users\\\\haykh\\\\AppData\\\\Local\\\\Android\\\\Sdk" > local.properties
powershell -File scripts/fetch-native.ps1
./gradlew assembleDebug
```
(Если `sdkmanager.bat` нет по этому пути — найти его в `$LOCALAPPDATA/Android/Sdk/cmdline-tools/*/bin`. `local.properties` проще записать через Write: `sdk.dir=C\:\\Users\\haykh\\AppData\\Local\\Android\\Sdk`.)
Expected: `BUILD SUCCESSFUL`, `app/build/outputs/apk/debug/app-debug.apk` содержит `lib/x86_64/libbaresip.so` (`unzip -l … | grep libbaresip`).

- [ ] **Step 7: Написать `docs/native.md`**

```markdown
# Нативные библиотеки

APK линкуется с библиотеками из `distribution.video/` (не в git). Получить готовые:

    powershell -File scripts\fetch-native.ps1

Пересобрать (при обновлении baresip из upstream):
1. Включить ВМ hhpbx-build (192.168.109.131, Debian 13, пользователь hayk).
2. `scp scripts/build-native.sh hayk@192.168.109.131:/var/tmp/` и запустить `/var/tmp/build-native.sh <коммит libbaresip-android ветки video>` (в фоне, лог в /var/tmp/build-native.log).
3. Опубликовать `distribution.video-<ver>.tar.gz` и `sources.txt` релизом `native-<ver>`, поднять версию по умолчанию в `scripts/fetch-native.ps1`.

Версия NDK в скрипте должна совпадать с `ndkVersion` в `app/build.gradle.kts`.
Сборка включает GPL-компоненты (x264, x265, bcg729, ZRTPCPP) — поэтому HHPhone распространяется под GPL.
```

- [ ] **Step 8: Коммит**

```bash
git add .gitignore gradle/wrapper/gradle-wrapper.jar scripts/build-native.sh scripts/fetch-native.ps1 docs/native.md
git commit -m "Сборка нативных библиотек на hhpbx-build и загрузка из релиза native-82.1.0"
git push
```

---

### Task 2: Flavor `hh`: applicationId, версия, языки, подпись

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml` (junit)
- Create: `app/src/hh/res/xml/locales_config.xml`
- Create: `app/src/hh/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHLocale.kt`
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/BaresipApp.kt` (одна строка в `onCreate`)
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/BaresipService.kt:173` (адресат события — свой пакет)
- Test: `app/src/test/kotlin/com/tutpro/baresip/plus/HHLocaleTest.kt`

**Interfaces:**
- Produces: варианты сборки `hhDebug` / `hhRelease` (`./gradlew assembleHhDebug`, `assembleHhRelease`, `testHhDebugUnitTest`); каталог `app/src/hh/res/` для переопределения ресурсов; `fun hhFallbackLanguage(systemLanguage: String, appLocalesEmpty: Boolean): String?` и `object HHLocale { fun apply(context: Context) }`.

- [ ] **Step 1: Тест (падает)**

`app/src/test/kotlin/com/tutpro/baresip/plus/HHLocaleTest.kt`:
```kotlin
package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HHLocaleTest {
    @Test fun supportedSystemLanguageKeepsSystem() {
        assertNull(hhFallbackLanguage("ru", appLocalesEmpty = true))
        assertNull(hhFallbackLanguage("hy", appLocalesEmpty = true))
        assertNull(hhFallbackLanguage("en", appLocalesEmpty = true))
    }

    @Test fun foreignSystemLanguageFallsBackToRussian() {
        assertEquals("ru", hhFallbackLanguage("de", appLocalesEmpty = true))
        assertEquals("ru", hhFallbackLanguage("zh", appLocalesEmpty = true))
    }

    @Test fun userChoiceIsNeverOverridden() {
        assertNull(hhFallbackLanguage("de", appLocalesEmpty = false))
    }
}
```

`gradle/libs.versions.toml`: в `[versions]` добавить `junit = "4.13.2"`, в `[libraries]` — `junit = { module = "junit:junit", version.ref = "junit" }`.
`app/build.gradle.kts`, в `dependencies { … }` в конец: `testImplementation(libs.junit)`.

Run: `./gradlew testDebugUnitTest --tests '*HHLocaleTest*'`
Expected: FAIL — `Unresolved reference: hhFallbackLanguage`.

- [ ] **Step 2: `HHLocale.kt`**

```kotlin
package com.tutpro.baresip.plus

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

private val HH_LANGUAGES = setOf("ru", "hy", "en")

/** Язык, который надо выставить приложению, или null — оставить как есть. */
fun hhFallbackLanguage(systemLanguage: String, appLocalesEmpty: Boolean): String? =
    if (appLocalesEmpty && systemLanguage !in HH_LANGUAGES) "ru" else null

object HHLocale {
    fun apply(context: Context) {
        // ponytail: per-app locale есть только с Android 13; на 9–12 при чужом системном языке будет английский.
        // Если понадобится — переопределять Configuration в attachBaseContext всех Activity и сервиса.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        val lang = hhFallbackLanguage(Locale.getDefault().language, lm.applicationLocales.isEmpty) ?: return
        lm.applicationLocales = LocaleList.forLanguageTags(lang)
    }
}
```

В `BaresipApp.onCreate()` сразу после `super.onCreate()` добавить строку `HHLocale.apply(this)`.

Run: `./gradlew testDebugUnitTest --tests '*HHLocaleTest*'`
Expected: PASS (3 теста).

- [ ] **Step 3: Flavor, фильтр языков, подпись**

В начало `app/build.gradle.kts` (после `import`):
```kotlin
import java.util.Properties

// Постоянный ключ подписи HHPhone вне репозитория: ~/.hhphone/keystore.properties + android-release.jks.
// Терять нельзя: APK с другим ключом не встанет поверх установленного.
val hhReleaseKeys = Properties().apply {
    val f = File(System.getProperty("user.home"), ".hhphone/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
```
Внутри `configure<ApplicationExtension> { … }` после блока `defaultConfig { … }`:
```kotlin
    flavorDimensions += "brand"
    productFlavors {
        create("hh") {
            dimension = "brand"
            applicationId = "am.dgsolutions.hhphone"
            versionCode = 1
            versionName = "0.1.0"
            androidResources.localeFilters += listOf("en", "ru", "hy")
        }
    }
    signingConfigs {
        create("hhRelease") {
            if (!hhReleaseKeys.isEmpty) {
                storeFile = file(hhReleaseKeys.getProperty("storeFile"))
                storePassword = hhReleaseKeys.getProperty("password")
                keyAlias = hhReleaseKeys.getProperty("keyAlias")
                keyPassword = hhReleaseKeys.getProperty("password")
            }
        }
    }
```
В `buildTypes { release { … } }` добавить строку:
```kotlin
            if (!hhReleaseKeys.isEmpty) signingConfig = signingConfigs.getByName("hhRelease")
```
Если AGP не принимает `androidResources.localeFilters` внутри flavor — использовать `resourceConfigurations += listOf("en", "ru", "hy")` там же (старое имя того же свойства).

- [ ] **Step 3a: События сервиса — в свой пакет**

`BaresipService.kt:173` жёстко шлёт внутренние события в `com.tutpro.baresip.plus`: с новым `applicationId` HHPhone их не получит (а установленный рядом baresip+ — получит). Заменить строку
```kotlin
        intent.setPackage("com.tutpro.baresip.plus")
```
на
```kotlin
        intent.setPackage(packageName)
```
Других жёстких адресатов нет (проверено `grep -rn "setPackage\|package:" app/src/main/kotlin`). Строки-действия вида `com.tutpro.baresip.plus.EVENT` / `.REGISTER` и ID каналов уведомлений не трогать — это имена внутри приложения, они не конфликтуют между пакетами.

- [ ] **Step 4: `locales_config` и манифест flavor**

`app/src/hh/res/xml/locales_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="ru" />
    <locale android:name="hy" />
    <locale android:name="en" />
</locale-config>
```
`app/src/hh/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:localeConfig="@xml/locales_config"
        tools:replace="android:localeConfig" />
</manifest>
```
Если сборка ругается, что в main нет `localeConfig` для `tools:replace` — убрать атрибут `tools:replace`.

- [ ] **Step 5: Проверить сборку и установку рядом с baresip+**

```bash
./gradlew testHhDebugUnitTest assembleHhDebug
aapt2=$(ls -d "$LOCALAPPDATA"/Android/Sdk/build-tools/*/ | tail -1)aapt2.exe
"$aapt2" dump badging app/build/outputs/apk/hh/debug/app-hh-debug.apk | grep -E "package:|locales:"
```
Expected: `package: name='am.dgsolutions.hhphone' versionCode='1' versionName='0.1.0'`, `locales: '--_--' 'en' 'ru' 'hy'` (без de/fr/…).
На эмуляторе (если AVD ещё нет — создать по Task 5 Step 1): `adb install -r app/build/outputs/apk/hh/debug/app-hh-debug.apk`, затем `adb install` официального baresip+ с F-Droid (`https://f-droid.org/repo/com.tutpro.baresip.plus_<код>.apk` — актуальный код взять со страницы пакета). Оба должны установиться (нет `INSTALL_FAILED_CONFLICTING_PROVIDER`) и запуститься.

- [ ] **Step 6: Коммит**

```bash
git add app/build.gradle.kts gradle/libs.versions.toml app/src/hh app/src/main/kotlin/com/tutpro/baresip/plus/HHLocale.kt app/src/main/kotlin/com/tutpro/baresip/plus/BaresipApp.kt app/src/main/kotlin/com/tutpro/baresip/plus/BaresipService.kt app/src/test
git commit -m "Flavor hh: applicationId am.dgsolutions.hhphone, ru/hy/en с фолбэком на русский, подпись"
git push
```

---

### Task 3: Оформление HHDesign — цвета, иконки, бренд в строках, «О программе»

**Files:**
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/Colors.kt`
- Modify (в G:\HHDesign): `brand/products.json`; сгенерированные `brand/apps/phone/*`
- Create: `app/src/hh/res/drawable-nodpi/hh_launcher_foreground.png`
- Create: `app/src/hh/res/mipmap-anydpi/ic_launcher.xml`, `app/src/hh/res/mipmap-anydpi/ic_launcher_round.xml`
- Create: `app/src/hh/res/values/hh_colors.xml`
- Create: `app/src/hh/res/drawable/ic_notification_b.xml`
- Create: `scripts/brand-strings.py`
- Create (генерируются): `app/src/hh/res/values/brand_strings.xml`, `app/src/hh/res/values-ru/brand_strings.xml`
- Create: `app/src/hh/res/values/hh_strings.xml`, `app/src/hh/res/values-ru/hh_strings.xml`

**Interfaces:**
- Consumes: flavor `hh` и `app/src/hh/res/` (Task 2).
- Produces: `scripts/brand-strings.py` — `python scripts/brand-strings.py` перегенерирует `brand_strings.xml` для `values` и `values-ru`; `python scripts/brand-strings.py --selftest` — самопроверка. Ключи, которые скрипт пропускает (их задаёт `hh_strings.xml` руками): `app_name`, `app_name_plus`, `about_title`, `about_title_plus`, `about_text`, `about_text_plus`. Task 4 читает `hh_strings.xml` и `brand_strings.xml` для армянского перевода.

- [ ] **Step 1: Цвета HHDesign в `Colors.kt`**

Заменить значения (имена констант не трогать — на них ссылается `Theme.kt`). Нейтральные — Fluent 2 (графит), бренд — HHDesign:
```kotlin
val Primary = Color(0xFF7A0C2A)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFF6E3E8)
val OnPrimaryContainer = Color(0xFF3D0717)
val Secondary = Color(0xFF424242)
val OnSecondary = Color(0xFFFFFFFF)
val SecondaryContainer = Color(0xFFEBEBEB)
val OnSecondaryContainer = Color(0xFF242424)
val Tertiary = Color(0xFF616161)
val OnTertiary = Color(0xFFFFFFFF)
val TertiaryContainer = Color(0xFFF0F0F0)
val OnTertiaryContainer = Color(0xFF242424)
val Error = Color(0xFFC50F1F)
val OnError = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFFDE7E9)
val OnErrorContainer = Color(0xFF751D1F)
val Background = Color(0xFFFAFAFA)
val OnBackground = Color(0xFF242424)
val Surface = Color(0xFFFAFAFA)
val OnSurface = Color(0xFF242424)
val SurfaceVariant = Color(0xFFEBEBEB)
val OnSurfaceVariant = Color(0xFF616161)
val SurfaceContainerLowest = Color(0xFFFFFFFF)
val SurfaceContainerLow = Color(0xFFF5F5F5)
val SurfaceContainer = Color(0xFFF0F0F0)
val SurfaceContainerHigh = Color(0xFFEBEBEB)
val SurfaceContainerHighest = Color(0xFFE0E0E0)
val Outline = Color(0xFF8A8A8A)
val OutlineVariant = Color(0xFFD1D1D1)

val PrimaryDark = Color(0xFFE0607A)
val OnPrimaryDark = Color(0xFF3D0717)
val PrimaryContainerDark = Color(0xFF9E1B3A)
val OnPrimaryContainerDark = Color(0xFFFFFFFF)
val SecondaryDark = Color(0xFFD6D6D6)
val OnSecondaryDark = Color(0xFF242424)
val SecondaryContainerDark = Color(0xFF3D3D3D)
val OnSecondaryContainerDark = Color(0xFFFFFFFF)
val TertiaryDark = Color(0xFFADADAD)
val OnTertiaryDark = Color(0xFF242424)
val TertiaryContainerDark = Color(0xFF333333)
val OnTertiaryContainerDark = Color(0xFFFFFFFF)
val ErrorDark = Color(0xFFF1707B)
val OnErrorDark = Color(0xFF3B0509)
val ErrorContainerDark = Color(0xFF751D1F)
val OnErrorContainerDark = Color(0xFFFDE7E9)
val BackgroundDark = Color(0xFF1F1F1F)
val OnBackgroundDark = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF1F1F1F)
val OnSurfaceDark = Color(0xFFFFFFFF)
val SurfaceVariantDark = Color(0xFF333333)
val OnSurfaceVariantDark = Color(0xFFD6D6D6)
val SurfaceContainerLowestDark = Color(0xFF141414)
val SurfaceContainerLowDark = Color(0xFF1A1A1A)
val SurfaceContainerDark = Color(0xFF292929)
val SurfaceContainerHighDark = Color(0xFF2E2E2E)
val SurfaceContainerHighestDark = Color(0xFF383838)
val OutlineDark = Color(0xFF8A8A8A)
val OutlineVariantDark = Color(0xFF525252)
```
Над первой строкой добавить комментарий: `// HHDesign (бордо #7A0C2A, графит Fluent 2) — G:\HHDesign / github.com/TheRainOfSoul/HHDesign`.
Зелёные/красные кнопки звонка — drawable `circle_green*`/`circle_red*`, их не трогать.

- [ ] **Step 2: Иконка продукта в HHDesign**

В `G:\HHDesign\brand\products.json` добавить строку после `"pms"` (с запятой у предыдущей):
```json
  "phone": { "name": "HH Phone", "glyph": "phone" }
```
```bash
cd /g/HHDesign && npm run icons && npm test
git add brand && git commit -m "Иконки продукта HH Phone (звонилка Android)" && git push
```
Expected: появился `brand/apps/phone/android-foreground.png` (432×432), тесты зелёные.

- [ ] **Step 3: Адаптивная иконка и иконка уведомлений в flavor**

```bash
mkdir -p /g/HHPhone/app/src/hh/res/drawable-nodpi /g/HHPhone/app/src/hh/res/mipmap-anydpi /g/HHPhone/app/src/hh/res/drawable /g/HHPhone/app/src/hh/res/values
cp /g/HHDesign/brand/apps/phone/android-foreground.png /g/HHPhone/app/src/hh/res/drawable-nodpi/hh_launcher_foreground.png
```
`app/src/hh/res/mipmap-anydpi/ic_launcher.xml` и `ic_launcher_round.xml` (одинаковые):
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/hh_launcher_foreground" />
    <monochrome android:drawable="@drawable/hh_launcher_foreground" />
</adaptive-icon>
```
`app/src/hh/res/values/hh_colors.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#7A0C2A</color>
</resources>
```
`app/src/hh/res/drawable/ic_notification_b.xml` — знак «HH» (из `G:\HHDesign\brand\hh-mark-white.svg`):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="96"
    android:viewportHeight="96">
    <group android:translateX="8" android:translateY="8">
        <path
            android:fillColor="#FFFFFF"
            android:pathData="M0,0h18v80h-18z M31,0h18v80h-18z M62,0h18v80h-18z M0,31h80v18h-80z" />
    </group>
</vector>
```
Проверить, что у upstream в `res/mipmap-*` нет растровых `ic_launcher*.png`, которые перебьют anydpi на старых API: `ls app/src/main/res | grep mipmap` — если есть `mipmap-xxxhdpi` и т. п. с `ic_launcher.png`, скопировать туда же в `app/src/hh/res/mipmap-<dpi>/ic_launcher.png` и `ic_launcher_round.png` соответствующие размеры из `G:\HHDesign\brand\apps\phone\` (48→mdpi, 64→hdpi ≈72, 128→xxhdpi ≈144, 256→xxxhdpi ≈192; брать ближайший больший и уменьшать через Python PIL).

- [ ] **Step 4: Скрипт бренда в строках — самопроверка (падает)**

`scripts/brand-strings.py`:
```python
"""Ребрендинг строк upstream: baresip / baresip+ -> HHPhone в пользовательском тексте.

Читает app/src/main/res/values{,-ru}/strings.xml, берёт строки со словом baresip,
заменяет его вне URL и пишет переопределения в app/src/hh/res/values{,-ru}/brand_strings.xml.
Запускать после каждого слияния с upstream. Ключи из SKIP задаются руками в hh_strings.xml.

usage: python scripts/brand-strings.py [--selftest]
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKIP = {"app_name", "app_name_plus", "about_title", "about_title_plus", "about_text", "about_text_plus"}
STRING = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.S)
URL = re.compile(r'(href="[^"]*"|https?://\S+)')
WORD = re.compile(r'(?<![\w/.\-])baresip\+?(?![\w/.\-])')


def rebrand(text):
    parts = URL.split(text)
    return "".join(p if URL.fullmatch(p) else WORD.sub("HHPhone", p) for p in parts)


def overrides(xml):
    out = []
    for name, attrs, body in STRING.findall(xml):
        if name in SKIP or 'translatable="false"' in attrs:
            continue
        new = rebrand(body)
        if new != body:
            out.append(f'    <string name="{name}"{attrs}>{new}</string>')
    return out


def main():
    for qual in ("values", "values-ru"):
        src = ROOT / "app/src/main/res" / qual / "strings.xml"
        lines = overrides(src.read_text(encoding="utf-8"))
        dst = ROOT / "app/src/hh/res" / qual / "brand_strings.xml"
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text('<?xml version="1.0" encoding="utf-8"?>\n'
                       "<!-- Сгенерировано scripts/brand-strings.py, руками не править -->\n"
                       "<resources>\n" + "\n".join(lines) + "\n</resources>\n", encoding="utf-8")
        print(f"{dst.relative_to(ROOT)}: {len(lines)} строк")


def selftest():
    assert rebrand("If checked, baresip starts") == "If checked, HHPhone starts"
    assert rebrand("baresip+\\'s Settings") == "HHPhone\\'s Settings"
    assert rebrand('<a href="https://github.com/baresip/baresip">x</a>') == '<a href="https://github.com/baresip/baresip">x</a>'
    assert rebrand("see https://github.com/juha-h/baresip-studio now") == "see https://github.com/juha-h/baresip-studio now"
    assert rebrand("com.tutpro.baresip.plus") == "com.tutpro.baresip.plus"
    assert rebrand("Если отмечено, baresip запускается") == "Если отмечено, HHPhone запускается"
    xml = ('<string name="a">baresip runs</string><string name="app_name" translatable="false">baresip</string>'
           '<string name="b" translatable="false">baresip x</string><string name="c">no brand</string>')
    assert overrides(xml) == ['    <string name="a">HHPhone runs</string>']
    print("selftest OK")


if __name__ == "__main__":
    selftest() if "--selftest" in sys.argv else main()
```
Сначала временно заменить тело `rebrand` на `return text` и убедиться, что самопроверка падает:
Run: `python -I scripts/brand-strings.py --selftest`
Expected: `AssertionError`. Вернуть тело `rebrand`.

- [ ] **Step 5: Самопроверка проходит, генерация**

Run: `python -I scripts/brand-strings.py --selftest && python -I scripts/brand-strings.py`
Expected: `selftest OK`, затем две строки с количеством (≈30 для values, ≈30 для values-ru).
Просмотреть `app/src/hh/res/values-ru/brand_strings.xml` глазами: «HHPhone» стоит в нормальных местах, URL целы.

- [ ] **Step 6: Название и «О программе» руками**

`app/src/hh/res/values/hh_strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name" translatable="false">HHPhone</string>
    <string name="app_name_plus" translatable="false">HHPhone</string>
    <string name="about_title">About HHPhone</string>
    <string name="about_title_plus">About HHPhone</string>
    <string name="about_text">@string/about_text_plus</string>
    <string name="about_text_plus">
        <![CDATA[
        <h1>HHPhone — SIP phone for HHPBX and other SIP PBXs</h1>
        <p>Voice and video calls, several accounts, TLS and SRTP.</p>
        <p>Source code: <a href="https://github.com/TheRainOfSoul/HHPhone">github.com/TheRainOfSoul/HHPhone</a> (GNU GPL).</p>
        <p>Based on <a href="https://github.com/juha-h/baresip-studio">baresip+</a> by TutPro Inc. (BSD 3-Clause)
        and the <a href="https://github.com/baresip/baresip">baresip</a> library.
        Includes x264, x265, bcg729 and ZRTPCPP under GNU GPL.</p>
        <p>DG Solutions, Armenia.</p>
        ]]>
    </string>
</resources>
```
`app/src/hh/res/values-ru/hh_strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="about_title">О HHPhone</string>
    <string name="about_title_plus">О HHPhone</string>
    <string name="about_text_plus">
        <![CDATA[
        <h1>HHPhone — SIP-телефон для HHPBX и других SIP-АТС</h1>
        <p>Голосовые и видеозвонки, несколько учёток, TLS и SRTP.</p>
        <p>Исходный код: <a href="https://github.com/TheRainOfSoul/HHPhone">github.com/TheRainOfSoul/HHPhone</a> (GNU GPL).</p>
        <p>Основан на <a href="https://github.com/juha-h/baresip-studio">baresip+</a> от TutPro Inc. (BSD 3-Clause)
        и библиотеке <a href="https://github.com/baresip/baresip">baresip</a>.
        Включает x264, x265, bcg729 и ZRTPCPP под GNU GPL.</p>
        <p>DG Solutions, Армения.</p>
        ]]>
    </string>
</resources>
```
Перед этим проверить в `app/src/main/kotlin/.../AboutScreen.kt`, какие ключи он реально использует (`grep -n "R.string.about" AboutScreen.kt`). Если используется другой ключ — переопределить именно его. Если `about_text` не существует в main, убрать строку `about_text` из `hh_strings.xml`.

- [ ] **Step 7: Сборка и визуальная проверка**

```bash
./gradlew assembleHhDebug && adb install -r app/build/outputs/apk/hh/debug/app-hh-debug.apk
adb shell am start -n am.dgsolutions.hhphone/com.tutpro.baresip.plus.MainActivity
adb exec-out screencap -p > "$SCRATCH/hh-main.png"
```
Просмотреть скриншот (Read): бордо в акцентах, нет голубого `#0CA1FD`. Открыть «О программе» (меню), снять скриншот, проверить текст. Включить тёмную тему эмулятора (`adb shell cmd uimode night yes`), снять скриншот, проверить `#E0607A`/`#9E1B3A`. Иконка в лаунчере — скриншот домашнего экрана.
Expected: нигде в видимом тексте нет «baresip» (кроме «О программе» с упоминанием основы).

- [ ] **Step 8: Коммит**

```bash
git add app/src/main/kotlin/com/tutpro/baresip/plus/Colors.kt app/src/hh scripts/brand-strings.py
git commit -m "Оформление HHDesign: цвета, иконка HH Phone, бренд в строках, «О программе»"
git push
```

---

### Task 4: Армянский перевод и полнота русского

**Files:**
- Create: `app/src/hh/res/values-hy/strings.xml`
- Create: `app/src/hh/res/values-hy/hh_strings.xml`
- Create: `app/src/hh/res/values-ru/missing_strings.xml` (только если есть непереведённые в upstream ru)
- Create: `scripts/missing-strings.py`
- Modify: `app/build.gradle.kts` (lint: `MissingTranslation` — ошибка)

**Interfaces:**
- Consumes: `brand_strings.xml`, `hh_strings.xml` (Task 3) — бренд «HHPhone» в переводе берётся оттуда.
- Produces: `python scripts/missing-strings.py` — печатает ключи из `values/` (переводимые), которых нет в `values-ru`/`values-hy` с учётом `app/src/hh/res`; код выхода 1, если есть пропуски.

- [ ] **Step 1: Скрипт поиска пропусков (сначала он должен найти все строки hy)**

`scripts/missing-strings.py`:
```python
"""Переводимые строки из values/, которых нет в ru или hy (main + hh вместе).

usage: python scripts/missing-strings.py   -> код 1 и список, если есть пропуски
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
STRING = re.compile(r'<string name="([^"]+)"([^>]*)>', re.S)


def keys(qual, translatable_only=False):
    found = set()
    for base in ("app/src/main/res", "app/src/hh/res"):
        for f in (ROOT / base / qual).glob("*.xml") if (ROOT / base / qual).exists() else []:
            for name, attrs in STRING.findall(f.read_text(encoding="utf-8")):
                if not (translatable_only and 'translatable="false"' in attrs):
                    found.add(name)
    return found


def main():
    base = keys("values", translatable_only=True)
    bad = 0
    for lang in ("ru", "hy"):
        missing = sorted(base - keys(f"values-{lang}"))
        bad += len(missing)
        print(f"{lang}: {len(missing)} пропущено", *missing, sep="\n  ")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
```
Run: `python -I scripts/missing-strings.py`
Expected: код 1; `hy: ~410 пропущено`, `ru: ~13 пропущено`.

- [ ] **Step 2: Дописать русский**

Для каждого ключа из списка `ru` взять английский текст из `app/src/main/res/values/strings.xml` (или `brand_strings.xml`, если ключ там) и написать перевод в `app/src/hh/res/values-ru/missing_strings.xml` (формат как в upstream `values-ru/strings.xml`: те же экранирования `\'`, `%1$s`, CDATA).
Run: `python -I scripts/missing-strings.py`
Expected: `ru: 0 пропущено`.

- [ ] **Step 3: Армянский перевод**

Перевести все ключи из списка `hy` в `app/src/hh/res/values-hy/strings.xml`, пачками по ~60 ключей (коммит после каждой пачки допустим). Правила:
- Источник — английский текст после ребрендинга (если ключ есть в `app/src/hh/res/values/brand_strings.xml` — брать оттуда); русский upstream — как подсказка смысла.
- Бренд «HHPhone» не переводится; `baresip` остаётся только в ссылках.
- Сохранять плейсхолдеры (`%1$s`, `%d`), `\'`, `\n`, HTML/CDATA, атрибуты `formatted="false"` как в оригинале.
- Восточноармянский, современная ИТ/телефонная лексика: звонок — զանգ, вызов/входящий — մուտքային զանգ, удержание — սպասման մեջ դնել, переадресация — վերահասցեավորում, голосовая почта — ձայնային փոստ, учётная запись — հաշիվ, контакт — կոնտակտ, настройки — կարգավորումներ.
- `app/src/hh/res/values-hy/hh_strings.xml` — `about_title`, `about_title_plus`, `about_text_plus` по образцу русского `hh_strings.xml`.

Run: `python -I scripts/missing-strings.py`
Expected: код 0, `ru: 0`, `hy: 0`.

- [ ] **Step 4: Lint как сторож после слияний**

В `app/build.gradle.kts` внутри `configure<ApplicationExtension> { … }`:
```kotlin
    lint {
        error += "MissingTranslation"
        checkOnly += "MissingTranslation"
    }
```
Run: `./gradlew lintHhDebug`
Expected: `BUILD SUCCESSFUL` (с фильтром `localeFilters` lint проверяет только en/ru/hy). Если lint ругается на ru/hy-ключи из `main`, которые на деле переопределены во flavor, — это значит, что ключ пропущен; дописать.

- [ ] **Step 5: Проверка на эмуляторе**

```bash
./gradlew assembleHhDebug && adb install -r app/build/outputs/apk/hh/debug/app-hh-debug.apk
adb shell cmd locale set-app-locales am.dgsolutions.hhphone --locales hy
adb shell am force-stop am.dgsolutions.hhphone && adb shell am start -n am.dgsolutions.hhphone/com.tutpro.baresip.plus.MainActivity
```
Скриншоты главного экрана, списка учёток, настроек, «О программе» на hy, затем `--locales ru` и `--locales en`. Затем `adb shell cmd locale set-app-locales am.dgsolutions.hhphone --locales ""` и системный язык de (`adb shell "settings put system system_locales de-DE"` или через Настройки эмулятора) → приложение на русском.
Expected: армянский текст отображается (шрифт есть в системе), нет английских остатков в hy/ru, при de — русский.

- [ ] **Step 6: Коммит**

```bash
git add app/src/hh/res/values-hy app/src/hh/res/values-ru scripts/missing-strings.py app/build.gradle.kts
git commit -m "Армянский перевод, дополнение русского, lint MissingTranslation"
git push
```

---

### Task 5: Живая проверка звонков через HHPBX

**Files:**
- Create: `docs/testing.md`

**Interfaces:**
- Consumes: `app-hh-debug.apk` (Tasks 2–4); ВМ hhpbx-test 192.168.109.132 (пользователь hayk, sudo без пароля) с HHPBX.

- [ ] **Step 1: Эмулятор телефона**

```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator" -list-avds
```
Если нет телефонного AVD с API ≥ 33 x86_64:
```bash
SDKM="$LOCALAPPDATA/Android/Sdk/cmdline-tools/latest/bin"
"$SDKM/sdkmanager.bat" "system-images;android-35;google_apis;x86_64"
echo no | "$SDKM/avdmanager.bat" create avd -n hhphone35 -k "system-images;android-35;google_apis;x86_64" -d pixel_6
```
Запуск в фоне: `emulator -avd hhphone35 -camera-back virtualscene -camera-front emulated` (`run_in_background`), ждать `adb wait-for-device` и `sys.boot_completed=1`.

- [ ] **Step 2: ВМ hhpbx-test и номера**

```bash
"/c/Program Files (x86)/VMware/VMware Workstation/vmrun.exe" -T ws start "F:\Wondershare\Clone of Debian 12.x 64-bit.vmx" nogui
ssh hayk@192.168.109.132 'sudo date -u -s "'"$(date -u '+%Y-%m-%d %H:%M:%S')"'"; systemctl is-active freeswitch; sudo fs_cli -x "show registrations"; sudo fs_cli -x "global_getvar global_codec_prefs"; sudo fs_cli -x "list_users" | head'
```
Expected: FreeSWITCH active; есть номера 100 и 101 (пароли `Tst100pass!x` / `Tst101pass!x`). Если номеров нет — HHPBX на ВМ не настроен: выполнить `linux/tests/vm/call-setup.sh` из `G:\HHPBX` по `memory/hhpbx-linux` (скопировать `windows/tests/{t_call.py,t_uuid.php,t_theme.php}` в `/root/hhpbx-tests`). Если ВМ откачена к «чистому Debian» без HHPBX — остановиться и спросить пользователя, ставить ли HHPBX.
Записать домен АТС (`list_users` → поле domain) и есть ли видеокодек (`H264`/`VP8`) в `global_codec_prefs`.

- [ ] **Step 3: Учётка вручную и регистрация**

В HHPhone на эмуляторе: «Учётки» → добавить `sip:100@<домен>`, пароль `Tst100pass!x`, outbound proxy `sip:192.168.109.132;transport=udp` (если домен не резолвится с эмулятора). Через `adb shell input` или руками по скриншотам.
Проверка: `ssh hayk@192.168.109.132 'sudo fs_cli -x "show registrations"' | grep 100`
Expected: регистрация 100 с user-agent baresip.

- [ ] **Step 4: Входящий при свёрнутом приложении**

```bash
adb shell input keyevent KEYCODE_HOME
ssh hayk@192.168.109.132 'sudo fs_cli -x "originate {origination_caller_id_number=101,origination_caller_id_name=Test}user/100@<домен> &playback(tone_stream://%(2000,0,440);loops=5)"'
```
Скриншот через 3 с: экран входящего звонка HHPhone. Ответить (`adb shell input` по координатам кнопки с скриншота), `fs_cli -x "show channels"` → канал в состоянии ACTIVE.
Expected: звонок пришёл в свёрнутое приложение, ответ работает, сброс со стороны телефона закрывает канал.

- [ ] **Step 5: Исходящий голос и видео-эхо**

С эмулятора набрать `*9196` (эхо-тест FusionPBX) голосовым вызовом → `fs_cli -x "show channels"` показывает канал с `echo`. Затем видеовызов на `*9196`: скриншот — на экране видео (эхо виртуальной камеры).
Если в `global_codec_prefs` нет видеокодека — видео не согласуется: зафиксировать это в отчёте («HHPBX по умолчанию без видеокодеков — решение для HHPBX отдельно»), видеопроверку считать заблокированной, а не проваленной.

- [ ] **Step 6: Не-HHPBX сценарий — TLS**

Если в HHPBX включён TLS-профиль (порт 5061): вторая учётка `sip:101@<домен>;transport=tls` с proxy `sip:192.168.109.132:5061;transport=tls`, в учётке снять «проверять сертификат» (самоподписанный). Регистрация 101 видна в `show registrations`. Если TLS на ВМ не включён — пропустить и записать в отчёт.

- [ ] **Step 7: `docs/testing.md`**

```markdown
# Проверка HHPhone

## На эмуляторе (каждый выпуск)
1. `./gradlew testHhDebugUnitTest lintHhDebug assembleHhDebug`, `python -I scripts/brand-strings.py --selftest`, `python -I scripts/missing-strings.py`.
2. ВМ hhpbx-test 192.168.109.132: номера 100/101 (`Tst100pass!x` / `Tst101pass!x`).
3. Учётка 100 → регистрация (`fs_cli -x "show registrations"`).
4. Входящий при свёрнутом приложении:
   `fs_cli -x "originate {origination_caller_id_number=101}user/100@<домен> &playback(tone_stream://%(2000,0,440);loops=5)"`.
5. Исходящий на `*9196` (эхо), видеовызов на `*9196`.
6. Языки: `adb shell cmd locale set-app-locales am.dgsolutions.hhphone --locales hy|ru|en`.

## На реальном телефоне (пользователь)
1. Установить APK из GitHub Releases, разрешить микрофон, камеру, уведомления, телефонные аккаунты.
2. В настройках Android снять оптимизацию батареи для HHPhone (Xiaomi: «Автозапуск» вкл., «Контроль активности» → «Нет ограничений»; Huawei: «Запуск приложений» → вручную, все три переключателя).
3. Добавить учётку, заблокировать экран, позвонить на номер с другого телефона через 10 минут — звонок должен прийти.
4. Видеозвонок, Bluetooth-гарнитура: ответ кнопкой гарнитуры.
```

- [ ] **Step 8: Коммит**

```bash
git add docs/testing.md
git commit -m "Памятка проверки HHPhone"
git push
```

---

### Task 6: README и выпуск v0.1.0

**Files:**
- Modify: `README.md` (полностью заменить upstream-текст своим; ссылки на upstream сохранить)

**Interfaces:**
- Consumes: всё из Tasks 1–5.

- [ ] **Step 1: README**

```markdown
# HHPhone

SIP-звонилка для Android: HHPBX и любые SIP-АТС. Голос и видео, несколько учёток, TLS/SRTP.
Языки: русский, армянский, английский. Android 9+.

Основан на [baresip+](https://github.com/juha-h/baresip-studio/tree/video) (TutPro Inc., BSD 3-Clause)
и библиотеке [baresip](https://github.com/baresip/baresip). Нативные библиотеки включают GPL-компоненты
(x264, x265, bcg729, ZRTPCPP), поэтому HHPhone распространяется под **GNU GPL**; исходный текст лицензии baresip-studio — в `LICENSE`.

## Сборка

Нужны: Android SDK (NDK 30.0.16248370, CMake 3.31.6), JDK 21, `gh`.

    powershell -File scripts\fetch-native.ps1
    gradlew assembleHhDebug

Выпуск: `gradlew assembleHhRelease` (ключ `%USERPROFILE%\.hhphone\keystore.properties`, см. app/build.gradle.kts).
Пересборка нативных библиотек — `docs/native.md`. Проверка — `docs/testing.md`.

## Обновление из upstream

    git fetch upstream
    git merge upstream/video
    python scripts/brand-strings.py
    python scripts/missing-strings.py   # перевести новые строки ru/hy

Наш код: flavor `app/src/hh/`, `HH*.kt`, `Colors.kt`, `BaresipApp.kt` (одна строка), `BaresipService.kt` (`setPackage(packageName)`).
```

- [ ] **Step 2: Ключ подписи (один раз)**

Если `%USERPROFILE%\.hhphone\android-release.jks` нет:
```bash
mkdir -p ~/.hhphone
PASS=$(python -c "import secrets;print(secrets.token_urlsafe(24))")
"$JAVA_HOME/bin/keytool" -genkeypair -v -keystore ~/.hhphone/android-release.jks -alias hhphone -keyalg RSA -keysize 4096 -validity 36500 -storepass "$PASS" -keypass "$PASS" -dname "CN=HHPhone, O=DG Solutions, C=AM"
```
`~/.hhphone/keystore.properties` записать через Write (пути Windows):
```
storeFile=C:/Users/haykh/.hhphone/android-release.jks
keyAlias=hhphone
password=<PASS>
```
(Если `JAVA_HOME` пуст — `keytool` из `C:\Program Files\Android\Android Studio\jbr\bin`.) Сообщить пользователю: ключ и пароль в `%USERPROFILE%\.hhphone`, сделать резервную копию вне ПК.

- [ ] **Step 3: Сборка выпуска и проверка подписи**

```bash
./gradlew testHhDebugUnitTest lintHhDebug assembleHhRelease
apksigner=$(ls -d "$LOCALAPPDATA"/Android/Sdk/build-tools/*/ | tail -1)apksigner.bat
"$apksigner" verify --print-certs app/build/outputs/apk/hh/release/app-hh-release.apk | head -3
adb install -r app/build/outputs/apk/hh/release/app-hh-release.apk
```
Expected: подпись `CN=HHPhone`; release-APK (с R8) запускается на эмуляторе и регистрирует учётку 100 (повтор Task 5 Step 3 — R8 может вырезать JNI-классы; upstream `proguard-rules.pro` должен это покрывать).

- [ ] **Step 4: Коммит, тег, релиз**

```bash
cp app/build/outputs/apk/hh/release/app-hh-release.apk "$SCRATCH/HHPhone-0.1.0.apk"
git add README.md && git commit -m "README HHPhone" && git push
git tag v0.1.0 && git push origin v0.1.0
gh release create v0.1.0 "$SCRATCH/HHPhone-0.1.0.apk" --title "HHPhone 0.1.0" \
  --notes "Первый выпуск: baresip+ 82.1.0 под брендом HH, языки ru/hy/en. Учётки вводятся вручную. Исходный код — GNU GPL."
```
Expected: релиз с APK на https://github.com/TheRainOfSoul/HHPhone/releases.
