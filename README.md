# Calorica

Ветка `main` содержит только семестровую версию мобильного приложения для учёта
питания, калорий и БЖУ с серверной частью.

## Документы для разработки

- [Границы семестрового MVP](docs/semester-mvp/scope.md) — функции Android и серверного API.
- [Домен и расчёты](docs/semester-mvp/domain.md).
- [Порядок реализации](docs/semester-mvp/plan.md).
- [Критерии приёмки SA-01–SA-17](docs/semester-mvp/acceptance.md).
- [ТЗ и архитектура третьего этапа](docs/semester-mvp/stage-3/technical-specification.md) — актуальный комплект требований SM/SB/SN.
- [Подробные доменные правила](docs/requirements/README.md).

Семестровые требования определяют объём разработки. Наличие операции в серверном
домене не означает, что для неё нужен отдельный Android-экран: границы клиента
и API перечислены в scope.md.

## Материалы защиты

- [Второй этап](docs/semester-mvp/stage-2/README.md): семестровое ТЗ, презентация и доклад.
- [Третий этап](docs/semester-mvp/stage-3/README.md): ТЗ с архитектурой, презентация и доклад.

## Отложенная ветка

Полная продуктовая документация, расширенные требования, ранние презентации и
планы развития сохранены в ветке `future/product-development`. Она не используется
для семестровой разработки и не является источником требований для `main`.
Нужные идеи из неё переносятся только после отдельного согласования, не слиянием
ветки целиком. Информация сохранена в Git; рабочие деревья других задач не удалялись.

## Текущее состояние

[Архитектурные решения](docs/architecture/README.md) описывают целевую реализацию;
текущее состояние учебного стенда приведено ниже.

Calorica — мобильное приложение для учёта питания, калорий и БЖУ с серверной частью.
Продуктовое поведение определяют [семестровые требования](docs/semester-mvp/scope.md).

В репозитории оставлен собираемый Android-каркас:

- один модуль `:app` с идентификатором `com.rurkk.calorica`;
- Kotlin, Jetpack Compose и Material 3;
- Hilt и KSP;
- Gradle Wrapper и каталог версий зависимостей;
- GitHub Actions для проверки сборки и ручной публикации debug APK.

При запуске приложение показывает только название Calorica. Продуктовые экраны,
локальная база и расчёты старого прототипа удалены. Бизнес-API и клиентское взаимодействие
с ним предстоит реализовать по новым требованиям.

В `backend/` добавлен отдельный Kotlin/Spring Boot-каркас: Spring JDBC, PostgreSQL,
Liquibase, закрытые по умолчанию маршруты, health/readiness и контейнерный запуск.
Каркас развёрнут на учебном стенде с HTTPS и автоматическим деплоем.
Бизнес-API и пользовательские сессии ещё не реализованы.
Инструкции: [backend](backend/README.md), [CI/CD и размещение](deploy/backend/README.md).

Старый прототип, черновики и AI-инструкции доступны в истории Git до этой очистки.
Данные ранее установленного приложения на устройствах эта очистка репозитория не удаляет.

## Инфраструктура учебного стенда

Calorica размещена на общем VPS Yarumo: `62.84.122.55`, Ubuntu 24.04,
2 CPU и около 2 ГБ RAM. Стенд настроен и проверен 30 сентября 2026 года.

| Адрес | Назначение |
| --- | --- |
| [caloricaitmo.duckdns.org](https://caloricaitmo.duckdns.org) | Базовый адрес API; веб-интерфейса здесь нет |
| [Readiness](https://caloricaitmo.duckdns.org/actuator/health/readiness) | Готовность приложения и БД |
| [Liveness](https://caloricaitmo.duckdns.org/actuator/health/liveness) | Работоспособность приложения |
| [GitHub](https://github.com/rurkk/Calorica) | Репозиторий |
| [GitHub Actions](https://github.com/rurkk/Calorica/actions) | Проверки, выпуск и деплой |

- Обвязка: Nginx → backend на `127.0.0.1:18090` → PostgreSQL в отдельной
  Docker-сети проекта `calorica-backend`. Порт БД наружу не опубликован.
- HTTPS: сертификат Let's Encrypt, автоматическое продление через Certbot.
  HTTP перенаправляется на HTTPS; пробное продление успешно проверено.
- Стек сервера: Kotlin, Spring Boot, Java 21, PostgreSQL 17, Liquibase,
  Docker Compose.
- Лимиты контейнеров: backend — 256 MiB RAM / 0,35 CPU, PostgreSQL —
  96 MiB / 0,15 CPU. Суммарный потолок — **352 MiB и половина ядра**;
  это лимиты, а не постоянно занятые ресурсы.
- CI/CD: изменения backend в `main` проходят проверки, публикуются в GHCR
  и разворачиваются по SSH пользователем `calorica-deploy`. Сборка выполняется
  в GitHub Actions, сервер загружает готовый образ по digest. Конфигурация
  Nginx применяется отдельно на хосте.
- Каталог на сервере — `/opt/calorica`, активный выпуск — `/opt/calorica/current`,
  настройки и секреты — `/opt/calorica/shared/backend.env`. Перед деплоем
  создаётся локальная копия БД. Секреты и приватные ключи в Git не входят.

Административное подключение:

```bash
ssh -i ~/.ssh/id_ed25519 valerochka@62.84.122.55
```

Подробные инструкции: [деплой и откат](deploy/backend/README.md),
[Nginx и HTTPS](deploy/nginx/README.md).
Регулярные внешние резервные копии и мониторинг пока не настроены.

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

`Android manual prerelease` запускается вручную, создаёт prerelease в GitHub и прикладывает debug APK.

`Backend CI` независимо проверяет сервер и инфраструктуру. После успешных проверок
изменений backend в `main` настроены выпуск контейнера в GHCR и автоматический
деплой по digest на учебный стенд. Документация не запускает сборки.
