# Backend Calorica

Самостоятельная сборка, не подключённая к Android Gradle-проекту: JDK 21,
Gradle 8.14.3, Kotlin 2.2.21, Spring Boot 3.5.16, Spring JDBC, PostgreSQL 17.11,
Liquibase. Docker builder использует ту же версию Gradle; build/runtime base-образы
закреплены по digest и требуют планового обновления для security patches. Совместимость выбранной ветки Boot с Java/Gradle проверена по
[официальной документации](https://docs.spring.io/spring-boot/3.5/system-requirements.html).

T03 добавляет аккаунты, JWT/refresh-сессии и nullable-профиль по контракту1.0.0.
Каталог, дневник и расчёты реализуются отдельными задачами; это ещё не весь MVP.
Миграция001 создаёт схему, 010 — accounts/sessions/refresh hashes/profiles;
таблицы Liquibase находятся в public. Применённые changesets не изменять.
Детали auth, ключа, proxy/rate limits и deadline — [accounts](src/main/kotlin/ru/calorica/accounts/README.md).

## Локальный запуск

Требуются Docker Engine и Compose v2 с `up --wait` (либо Compose v5).
Из `backend/`:

```sh
cp .env.example .env
# Укажите свой POSTGRES_PASSWORD в .env.
docker compose up --build -d --wait
curl --fail http://127.0.0.1:8080/actuator/health/readiness
curl -i http://127.0.0.1:8080/api/products # ожидается 401, функции ещё нет
# Остановка сохраняет данные:
docker compose down
```

API доступен только на loopback; PostgreSQL не публикует порт. Том сохраняется
между запусками. `down -v` удаляет данные и не является штатной остановкой.
Для другого HTTP-порта задайте `API_PORT` в `.env`. Не используйте локальный пример
пароля в production. Заполненные `.env` исключены из Git и Docker build context.

Запуск JVM без контейнера: предоставьте отдельную PostgreSQL и экспортируйте
`DB_URL=jdbc:postgresql://127.0.0.1:5432/calorica`, `DB_USERNAME`, `DB_PASSWORD`.
Пароли передавайте средствами окружения/секретов, не аргументами командной строки.
Затем `./gradlew bootRun`. Для production: `SPRING_PROFILES_ACTIVE=prod`.
Обязательные значения БД не имеют встроенных defaults. Дополнительно доступен
`DB_POOL_SIZE` (по умолчанию 5 локально, 2 в профиле prod). JSON настроен на UTC;
контейнер запускает JVM в UTC. Экономный профиль VPS описан в
[deploy/backend/README.md](../deploy/backend/README.md#экономный-профиль-учебного-стенда).
Для такого же часового пояса JVM вне Docker задайте `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`.
Доменные моменты будут Instant, числа — BigDecimal.

## Проверки

```sh
# JDK 21; DB_* должны указывать на отдельную одноразовую тестовую БД.
./gradlew check bootJar
```

`check` включает `integrationTest`: запуск HTTP-сервера с реальной PostgreSQL,
начальную миграцию, повторное применение и validate Liquibase, минимальные health
ответы и запрет доступа к будущим данным/служебным маршрутам. H2 не используется.
Проверки требуют чистой БД данной версии (не production). `test` проверяет политики credentials/JWT/rate/IP; `integrationTest` дополнительно
проверяет auth/profile, SQL-гонки и реальные HTTP deadline/body limits.
CI дополнительно собирает контейнер, проверяет запуск и отказ БД:
readiness → 503, liveness → 200. Переход с предыдущего выпущенного backend, реальные
перезапуски JVM/PostgreSQL
с предметными данными и функциональная совместимость возврата проверяются отдельным
[прогоном T17](../deploy/backend/PERSISTENCE.md). Readiness старого образа сам по себе
не подтверждает корректность чтения новых составов и дневника.

## API и границы

Публичны только GET `/actuator/health`, `/actuator/health/liveness`,
`/actuator/health/readiness`. Детали компонентов скрыты. Readiness включает БД
и параметризованный SQL проверки схемы; liveness не зависит от доступности БД.
Маршруты вне /api/v1 закрыты через Spring Security denyAll.
Нет Basic/form login, cookie-сессий, встроенного пользователя или тестового bypass.
Сессии реализованы по docs/architecture/runtime-policies.md; /api/v1/accounts
и /api/v1/profile используют контракт1.0.0. Защищённые /api/v1 требуют активного Actor,
/admin дополнительно требует ADMIN. Проверка владельца остаётся обязанностью предметного сценария.

Ошибка: `application/problem+json`, стандартные поля Problem Details
`type`, `title`, `status`, `detail`, дополнительные `code` и `correlationId`.
`instance` может добавляться MVC; клиенты не должны требовать его наличия.
`X-Request-Id` создаёт сервер; входной заголовок не считается доверенным.
Не возвращаются сообщения исключений, SQL или stack trace. В access-логах — ID,
HTTP-метод, статус и длительность, без URL/query, тела, email и токенов.
Конкретные доменные коды и OpenAPI добавляются вместе с реальными операциями.

Пакеты `accounts`, `profile`, `catalog`, `diary`, `analytics` фиксируют границы
будущих модулей. Внутри будут `api/application/domain/persistence` по мере появления
кода. Общая техническая инфраструктура — `platform`. Сценарии управляют правами
и транзакциями; SQL явный, без JPA, другие модули не пишут напрямую в таблицы каталога.
Согласованные документы требований и архитектуры этой задачей не изменяются.

CI/CD и эксплуатация: [deploy/backend/README.md](../deploy/backend/README.md).

## Учебный стенд

API: `https://calorica-backend.onrender.com`.
Проверка: `GET /actuator/health/readiness` → `200 {"status":"UP"}`.
С 1 ноября 2026 backend размещён на Render Free, PostgreSQL 17 — в Neon Free
во Франкфурте. Обновления из main автоматические: GitHub Actions → GHCR → Render
по digest; Markdown и документация не выпускают backend. HTTPS предоставляет Render.
Настройки, адреса, ограничения
тарифов и интеграция Codex — в [deploy/render/README.md](../deploy/render/README.md).
Прежний VPS недоступен; его Compose и Nginx сохранены как отдельные инструкции.
