# Calorica

## Материалы первого этапа

- [Согласованные требования](docs/requirements/README.md).
- [Презентация первого этапа, 8 слайдов](docs/presentation/Calorica-stage-1-final.pptx).

## Текущее состояние

Calorica — мобильное приложение для учёта питания, калорий и БЖУ с серверной частью.
Продуктовое поведение определяют [согласованные требования](docs/requirements/README.md).

В репозитории оставлен собираемый Android-каркас:

- один модуль `:app` с идентификатором `com.rurkk.calorica`;
- Kotlin, Jetpack Compose и Material 3;
- Hilt и KSP;
- Gradle Wrapper и каталог версий зависимостей;
- GitHub Actions для проверки сборки и ручной публикации debug APK.

При запуске приложение показывает только название Calorica. Продуктовые экраны,
локальная база и расчёты старого прототипа удалены. Сервер и клиентское взаимодействие
с ним предстоит реализовать по новым требованиям.

Старый прототип, черновики и AI-инструкции доступны в истории Git до этой очистки.
Данные ранее установленного приложения на устройствах эта очистка репозитория не удаляет.

## Локальная сборка

Для сборки нужны JDK 17 (toolchain) и Android SDK 37. Укажите Android SDK в `local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
```

Затем выполните:

```bash
./gradlew :app:assembleDebug
```

APK будет в `app/build/outputs/apk/debug/app-debug.apk`.

Проверка unit-тестов: `./gradlew :app:testDebugUnitTest`. Сейчас тестовых классов нет,
поэтому задача завершается со статусом `NO-SOURCE`.

## CI/CD

`Android CI` запускает unit-тесты и debug-сборку для pull request и изменений Android-кода в `main`.

`GitHub release` запускается вручную, создаёт prerelease в GitHub и прикладывает debug APK. Для доставки в Google Play потребуется отдельная настройка подписи и доступа к Play Console.
