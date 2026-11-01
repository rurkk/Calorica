# Краткая инструкция по отладке Calorica

При первом открытии приложение ожидает готовность сервера до 90 секунд:
экран «Сервер просыпается…» повторяет только GET readiness, пока не получит
JSON `status=UP`. После ошибки доступна кнопка «Повторить». Уход в фон отменяет
незавершённое ожидание; успешная проверка сохраняется в памяти ViewModel.
Обычные API-запросы по-прежнему ограничены 10 секундами, операции записи
автоматически не повторяются. Постоянного фонового прогрева нет.

## 1. Что установить на компьютер

| Инструмент | Зачем |
| --- | --- |
| [Android Studio](https://developer.android.com/studio) | Сборка, установка на телефон, debugger и Logcat в одном приложении |
| [Git](https://git-scm.com/downloads) | Скачать исходники и переключаться между версиями |
| [JDK 21 и JDK 17](https://adoptium.net/temurin/releases/) | JDK 21 — запуск Gradle и backend; JDK 17 — Android toolchain проекта |

В Android Studio откройте **SDK Manager** и установите **Android SDK Platform 37**,
**Android SDK Build-Tools** требуемой проектом версии и **Android SDK Platform-Tools**
(содержит `adb`). В настройках **Build Tools → Gradle → Gradle JDK** выберите JDK 21;
для команд терминала `JAVA_HOME` также должен указывать на JDK 21. JDK 17 должен
быть установлен и доступен Gradle как toolchain. Отдельно скачивать Gradle не нужно:
проект содержит Wrapper. Если IDE не поддерживает AGP проекта, обновите Android Studio,
не понижая версии в проекте. [Подробнее о JDK](https://developer.android.com/build/jdks).

Для работы только с Android Docker, SQL-клиент и инфраструктурные ключи не нужны.
Для локального backend дополнительно нужен **Python 3.9+** и общий `.env.infra`
от владельца; для просмотра БД удобен [DBeaver Community](https://dbeaver.io/download/).
Доступы — в [инструкции участника](team-onboarding.md).

## 2. Как запустить на своём телефоне

Нужны **Android 8.0+** и USB-кабель с передачей данных. На iPhone APK не запускается.

1. Скачайте проект и откройте папку `Calorica` в Android Studio, дождитесь Gradle Sync:

   ```sh
   git clone https://github.com/rurkk/Calorica.git
   cd Calorica
   ```

2. На телефоне: **Настройки → О телефоне → Номер сборки** — нажмите семь раз.
   Затем в **Для разработчиков** включите **Отладку по USB**. Названия меню могут отличаться.
3. Подключите телефон, разблокируйте его и подтвердите разрешение отладки с компьютера.
   В Windows при необходимости установите USB-драйвер производителя телефона.
4. В Android Studio выберите конфигурацию `app`, свой телефон и вариант `debug`,
   нажмите **Run ▶**. IDE соберёт, установит и запустит приложение.
5. Если телефона нет, создайте виртуальное устройство в **Device Manager**.
   На Android 11+ можно подключить настоящий телефон без кабеля через
   **Pair Devices Using Wi-Fi**; компьютер и телефон должны быть в одной Wi-Fi-сети.
   [Официальная инструкция подключения](https://developer.android.com/studio/run/device).

Проверка через терминал, если `adb` добавлен в `PATH` из `<Android SDK>/platform-tools`:

```sh
adb devices
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Устройство должно иметь статус `device`. При `unauthorized` подтвердите запрос
на телефоне; если список пуст — проверьте кабель, режим USB и драйвер.
В Windows используйте `gradlew.bat` вместо `./gradlew`.

**Если нужно только потестить готовую версию:** скачайте `calorica.apk` из
[GitHub Releases](https://github.com/rurkk/Calorica/releases), если выпуск уже опубликован,
откройте на телефоне и разрешите установку этому браузеру/файловому менеджеру.
Android Studio и USB-отладка для такой установки не нужны.
Версия, source SHA, постоянная подпись и повтор/откат — в
[инструкции выпуска Android](android-releases.md).
При конфликте подписи с установленной версией запросите APK с той же подписью;
удаление старого приложения сотрёт его локальные данные.

## 3. Чем смотреть ошибки

| Что проверить | Где и как |
| --- | --- |
| Падение или ошибка Android | **View → Tool Windows → Logcat**: выбрать телефон, фильтр `package:com.rurkk.calorica`; воспроизвести ошибку и скопировать stack trace |
| Значения переменных, ход кода | Поставить breakpoint слева от строки, нажать **Debug** вместо Run; выполнение остановится на этой строке |
| Ошибка сборки | Окно **Build**: найти первую причину ошибки и её `Caused by` |
| Ответ сервера | Браузер, `curl` или необязательный [Postman](https://www.postman.com/downloads/) |
| Ошибка на сервере | [Better Stack Live tail](https://telemetry.betterstack.com/team/t627028/tail?s=l2819108), вход по приглашению; искать ID из заголовка ответа `X-Request-Id` |
| Состояние БД | Neon Console или DBeaver; параметры подключения и правила — в [инструкции доступа](../deploy/access/README.md#sql-и-миграции) |

[Logcat](https://developer.android.com/studio/debug/logcat),
[debugger](https://developer.android.com/studio/debug) и
[команды adb](https://developer.android.com/tools/adb) — официальные справки.

Проверить учебный сервер без ключей можно так (Windows: `curl.exe`):

```sh
curl -i https://calorica-backend.onrender.com/actuator/health/readiness
```

Ожидается `200` и `{"status":"UP"}`. Render Free засыпает: первый ответ может
занять около минуты. Это проверка готовности сервера и БД, а не проверка функций приложения.

Для breakpoint в backend: из корня проекта запустите
`python3 scripts/infra/access.py dev-run --debug` с JDK 21 и `.env.infra`,
затем подключите в IDE **Remote JVM Debug** к `localhost:5005`. До подключения
debugger сервер ждёт; после него HTTP доступен на `http://localhost:8080`.
Используется общая dev-БД: изменения согласуйте с командой. Docker для этого
способа не требуется. Изолированный запуск через Docker — в [backend/README.md](../backend/README.md).

## 4. Что проверить и как передать баг

**Состояние на 1 ноября 2026:** Android показывает только надпись Calorica;
продуктовых экранов и подключения к API ещё нет. Сейчас на телефоне можно проверить
установку, запуск, поворот экрана, возврат из фона и отсутствие падений в Logcat.
У backend доступны health-проверки; `401` на `/api/products` ожидаем — бизнес-API
ещё не реализован. Регистрацию, дневник и расчёты проверяем после их реализации.

После появления функций проходите основной сценарий своей задачи на телефоне,
включая повторный запуск, потерю сети и возврат подключения; ожидаемый результат
берите из [критериев приёмки](semester-mvp/acceptance.md).

Передайте разработчику: **версию APK/commit, модель телефона и версию Android,
шаги воспроизведения, ожидаемый и фактический результат, время с часовым поясом,
stack trace и request ID**, если есть. Не прикладывайте пароли, токены и `.env.infra`.

Автоматическая проверка Android из корня:
`./gradlew :app:testDebugUnitTest :app:assembleDebug`.
Сейчас unit-тестов нет (`NO-SOURCE`); успешная сборка не заменяет проверку на телефоне.
