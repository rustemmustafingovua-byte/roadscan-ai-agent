# Release-збірка RoadScan AI Agent

Документ для того, хто збирає постачальний APK: ключі, версіонування, R8,
перевірка перед передачею замовнику та чек-лист Google Play.

## 1. Версія

Версія задається в `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 1          // ціле число, зростає з кожною публікацією
    versionName = "0.1.0"    // те, що бачить замовник
}
```

Правила:

* `versionCode` збільшується **завжди** перед новою передачею — Android не
  встановить APK з меншим або рівним кодом.
* `versionName` — семантичне версіонування: `0.1.0` (пілот), `1.0.0` (перший
  комерційний реліз), далі `1.1.0` для нових функцій і `1.0.1` для виправлень.
* Версія TFLite-моделі фіксується в `README.md` і в акті приймання: модель і
  застосунок постачаються разом.

## 2. Ключ підпису

Ключ постачальника зберігається **поза репозиторієм**. Один раз:

```bash
keytool -genkeypair -v \
  -keystore roadscan-release.jks \
  -alias roadscan \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=RoadScan AI, O=RoadScan, C=UA"
```

Поруч із `settings.gradle.kts` створюється `keystore.properties`:

```properties
storeFile=../secrets/roadscan-release.jks
storePassword=********
keyAlias=roadscan
keyPassword=********
```

Обидва файли вже в `.gitignore` (`keystore.properties`, `*.jks`, `*.keystore`).
Втрата ключа означає, що оновити встановлений застосунок буде неможливо —
зберігайте його в двох місцях і з паролем у менеджері секретів.

Якщо `keystore.properties` немає, `assembleRelease` збирає **непідписаний** APK:
це режим для CI-перевірки, а не для передачі замовнику.

## 3. Збірка

```bash
./gradlew :core:test                 # 88 тестів ядра
./gradlew :app:lintDebug             # 0 помилок (попередження — у звіті)
./gradlew :app:assembleRelease       # R8 + shrinkResources + підпис
ls -lh app/build/outputs/apk/release/app-release.apk
```

Release-збірка вмикає:

* `isMinifyEnabled = true` — R8, обфускація та видалення невикористаного коду;
* `isShrinkResources = true` — видалення невикористаних ресурсів (тому нові
  рядки не можна залишати «на потім» без використання);
* правила `app/proguard-rules.pro` для TFLite, Room, kotlinx.serialization,
  ліцензій і профілів калібрування.

Перевірка підпису:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

## 4. Що перевірити на release APK

R8 ламає саме те, що працює в debug, тому перед передачею проганяється
короткий сценарій (10–15 хвилин на пристрої):

1. Холодний старт, HUD, перемикання всіх чотирьох вкладок.
2. «Налаштування»: активація ліцензії ключем `RSL1…`, калібрування, реквізити.
3. «Звіт»: «Сформувати демо-звіт» → площі, кошторис у ₴, PDF/Excel/GeoJSON.
4. «Відео»: вибір ролика → аналіз → обидва режими монтажу → шаринг.
5. Польовий проїзд 2–3 хвилини: камера, GPS, акселерометр, завершення зміни.
6. Перевірка, що без ліцензії документи й відео мають водяний знак «ДЕМО».

Автоматизована частина: `./gradlew build` (тести + lint + assemble).

## 5. Google Play: чек-лист

* **Цільовий SDK** — 36 (актуальна вимога Play на 2026 рік).
* **16 КБ сторінки пам'яті** — обов'язково для нових застосунків. Поточна
  залежність `com.google.ai.edge.litert:litert:1.0.1` дає попередження
  `Aligned16KB` для `libtensorflowlite_jni.so`; перед публікацією оновити
  LiteRT до версії з 16 КБ-вирівнюванням або замінити на TensorFlow Lite
  з відповідним build-прапорцем.
* **Дозволи**: `CAMERA`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.
  У Play Console заповнити обґрунтування: камера — сканування покриття,
  геолокація — координати дефектів. Фонової геолокації немає.
* **Конфіденційність**: застосунок не має мережевих дозволів і не збирає
  аналітику. У формі Data safety вказати «дані не передаються».
* **Ліцензія**: безкоштовне завантаження + активація ключем, або закритий
  трек для конкретного замовника (internal/closed testing).
* **Розмір**: release APK ≈ 44 МБ (модель 26 МБ int8 + LiteRT). Для Play краще
  Android App Bundle: `./gradlew :app:bundleRelease`.
* **Іконка**: монохромна іконка для themed icons поки відсутня
  (`MonochromeLauncherIcon`), додати перед публікацією.

## 6. Артефакти для замовника

| Файл | Що це |
|---|---|
| `roadscan-ai-<version>.apk` | підписаний release APK |
| `license-<org>.txt` | ключ `RSL1.…` (випускає `tools/license.py`) |
| `price_catalog.json` | довідник цін регіону (₴, ПДВ 20 %) |
| `calibration-<vehicle>.json` | профіль калібрування під авто |
| `docs/COMMERCIAL.md` | комерційна пропозиція |
| `docs/UKRAINE.md` | локаль, ДСТУ, демо-ділянка |
| `docs/EULA.md`, `docs/PRIVACY.md` | правові документи |

## 7. Випуск ліцензії

```bash
python3 tools/license.py issue \
  --org "КП Шляхове господарство" \
  --tier PRO --days 365 \
  --features pdf,xlsx,geojson,gis,video \
  --out license-kp.txt
python3 tools/license.py verify license-kp.txt
```

Активація: deep link `roadscan://license?key=RSL1.…` або вставка ключа на
екрані «Налаштування». Мережа не потрібна.

## 8. Відкат

Якщо реліз зламався на об'єкті: попередній APK з тим самим `applicationId`
встановлюється поверх (`adb install -r` або передача файлу), але **лише** з
`versionCode` не меншим за встановлений. Тому перед кожним релізом
зберігається копія APK і профілю калібрування; дані сесій лежать у
`roadscan.db` і переносяться через експорт.
