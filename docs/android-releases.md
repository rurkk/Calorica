# Выпуски Android

## Что происходит автоматически

После push в `main` Android CI определяет изменённые компоненты, проверяет
release-автоматику, выполняет unit-тесты и debug-сборку. При изменении Android-кода
или конфигурации сборки он вызывает `Android release`: тесты, debug/release сборки,
проверка подписи, пакета и версии APK, затем публикация GitHub Release.
Изменения только backend, документов или workflow не выпускают новый APK.
PR не получает ключ подписи и не публикует релиз.

Скачать опубликованный `calorica.apk` можно в
[GitHub Releases](https://github.com/rurkk/Calorica/releases).
Это release APK, подписанный постоянным ключом, а не прежний debug APK.
Рядом опубликованы `release.json` (source SHA, версия, сертификат, SHA-256 APK)
и `SHA256SUMS`. Статус текущей реализации функций указан в описании релиза.

В Summary workflow находятся ссылки на APK, Release, точный исходный SHA,
сам workflow/логи, readiness стенда, backend CI и серверные логи.
Ссылки стенда справочные: Android workflow не подтверждает его успешный деплой.
Необязательные repository variables `CALORICA_DEPLOY_URL`,
`CALORICA_DEPLOY_LOGS_URL` и `CALORICA_LOGS_URL` переопределяют адреса ссылок.
Отчёты тестов и подготовленные артефакты дополнительно хранятся в Actions 14 дней.

## Постоянная подпись: однократная настройка владельцем

Нужны `keytool` из JDK, Python 3.9+ и авторизованный `gh` с правом управлять
Actions secrets/variables. Ключ хранится вне всех Git worktrees в приватном каталоге.
Не создавать новый ключ для каждой сборки: это нарушает установку обновлений.

Создать ключ или повторно проверить уже сохранённый:

```sh
python3 scripts/ci/android_signing_setup.py \
  --directory /absolute/private/path/calorica-android-signing
```

Каталог получает права `700`, ключ и `credentials.json` — `600`.
Скопируйте весь каталог в защищённое резервное хранилище владельца.
`credentials.json` содержит пароли; не отправляйте его в Git, чат или workflow artifacts.
`certificate-sha256.txt` — публичный отпечаток сертификата.
Если ключ уже создан, повторять генерацию в другом каталоге не нужно.

После проверки и разрешения владельца тот же вызов с `--upload` настраивает
именно `rurkk/Calorica`:

```sh
python3 scripts/ci/android_signing_setup.py \
  --directory /absolute/private/path/calorica-android-signing \
  --repo rurkk/Calorica --upload
```

Скрипт не заменяет существующие signing secrets/отпечаток: при их наличии
останавливается, чтобы исключить случайную ротацию ключа. Если загрузка прервалась,
владелец проверяет частично созданные настройки и восстанавливает их из того же
backup; новый ключ не генерировать.

| Настройка | Тип | Содержимое |
| --- | --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Secret | Base64 всего keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Secret | Пароль keystore |
| `ANDROID_KEY_ALIAS` | Secret | Alias ключа |
| `ANDROID_KEY_PASSWORD` | Secret | Пароль ключа |
| `ANDROID_SIGNING_CERT_SHA256` | Variable | SHA-256 DER-сертификата, hex без двоеточий |

Обычно публикация использует встроенный `GITHUB_TOKEN` с `contents: write`.
Для исторического SHA, в котором workflow отличается от текущей main, GitHub
может потребовать дополнительное право `Workflows: write`. Для такого случая
поддержан необязательный secret `ANDROID_RELEASE_TOKEN`: fine-grained PAT,
ограниченный этим репозиторием, с `Contents: write` и `Workflows: write`.
Он используется только в шагах работы с Releases. Без него GitHub может отклонить
резервирование/публикацию старого SHA; уже опубликованные assets не изменяются.
[Ограничение GitHub Releases API](https://docs.github.com/en/rest/releases/releases#create-a-release).

CI проверяет отпечаток фактической подписи через `apksigner`, package name и версии
через `aapt`. Временный keystore удаляется и не попадает в artifacts.
Обычная локальная `assembleRelease` без signing env создаёт неподписанный APK;
workflow такой APK не публикует. Debug использует обычный debug key.
Прежний debug APK нельзя обновить release APK с другой подписью. Для первого
перехода согласуйте перенос данных; удаление приложения удаляет локальные данные.

## Автоматическая версия и повтор неудачного выпуска

В `app/build.gradle.kts` заданы локальные минимальные значения версии.
CI передаёт `CALORICA_VERSION_CODE` и `CALORICA_VERSION_NAME` через окружение.
Следующая версия выше всех сохранённых резервов: `versionCode + 1`, patch + 1.
Новая major/minor автоматически не назначается. Первый выпуск использует как
минимум локальную версию; существующие теги учитываются при выборе следующего patch.
В исходники не делается отдельный version-коммит от CI.

Сначала создаётся **draft Release**, резервирующий версию и точный source SHA.
Успешная сборка заполняет его assets и публикует. Неудачная сборка оставляет резерв.
Не удаляйте drafts и не редактируйте технический metadata marker: по ним определяется
монотонная последовательность версий. Все выпуски сериализованы общей concurrency
группой; GitHub может заменить ожидающий запуск более новым, активный не отменяется.

Повторить можно двумя способами:

1. В прежнем Actions run выбрать **Re-run failed jobs** / **Re-run all jobs**:
   сохраняются исходные SHA и зарезервированная версия.
2. **Actions → Android release → Run workflow**, ветка **main**,
   `mode=retry`, `release_tag=android-vX.Y.Z` из Summary.

Повтор опубликованного выпуска не меняет APK и не назначает новую версию.
Если после оставшегося draft уже опубликована более новая версия, повтор draft
отклоняется. Выберите `mode=release`, чтобы выпустить текущую main с новой версией.
Ручной запуск с другой ветки не публикует релиз. Для обычного ручного выпуска:
`mode=release`, остальные поля пустые; источник — точный SHA main на момент запуска.

## Откат совместимой версии

Android не устанавливает меньший `versionCode` поверх большего. Поэтому rollback
повторно собирает код выбранного опубликованного выпуска, подписывает тем же ключом
и публикует **новую, большую** версию. Backend и БД этот workflow не меняет.

Допустимы только старые опубликованные релизы этого pipeline с metadata marker.
Legacy debug-релизы, произвольные ветки/SHA и drafts не принимаются.
В `app/build.gradle.kts` исходников заданы `caloricaApiEpoch` и
`caloricaDataEpoch`. Владелец Android повышает соответствующий epoch при
несовместимом изменении API или локального формата данных. Автоматика сравнивает
оба epoch целевого APK, последнего опубликованного APK и текущей main.
Совпадение epoch — необходимое условие, но оно не заменяет проверку сценариев
с действующим backend и данными пользователя. Повышать epoch при каждой миграции
не нужно, если старое приложение продолжает корректно читать данные.

1. Найдите известный рабочий тег и проверьте совместимость с действующим API,
   состоянием сессии и данными, которые уже записала последняя версия.
2. **Actions → Android release → Run workflow**, ветка **main**,
   `mode=rollback`, `release_tag=android-vX.Y.Z`.
3. Установите `compatibility_confirmed=true`, заполните `reason`: причина и
   результат проверки без пользовательских данных/секретов.
4. Сверьте source SHA и новую версию в Summary и `release.json`.
   Установите новый APK поверх текущего и пройдите основной сценарий.

При несовместимости epoch workflow прекращает выпуск. Нужен совместимый forward
fix; удаление приложения/данных и downgrade БД не выполняются.

## Проверки перед интеграцией

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/ci -p 'test_android_*.py'
actionlint .github/workflows/android.yml .github/workflows/release.yml
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Для живой приёмки дополнительно проверить публикацию, сертификат и SHA-256
скачанного APK, установку, обновление с тем же ключом, повтор и совместимый rollback.
Локальные проверки политик не подтверждают GitHub permissions и наличие secrets.

Официальные справки: [подпись APK](https://developer.android.com/studio/publish/app-signing),
[версии Android](https://developer.android.com/studio/publish/versioning),
[повтор GitHub Actions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs).
