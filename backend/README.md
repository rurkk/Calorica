# Backend Calorica: инфраструктурный каркас

Самостоятельная сборка, не подключённая к Android Gradle-проекту: JDK 21,
Gradle 8.14.3, Kotlin 2.2.21, Spring Boot 3.5.16, Spring JDBC, PostgreSQL 17.11,
Liquibase. Docker builder использует ту же версию Gradle; build/runtime base-образы
закреплены по digest и требуют планового обновления для security patches. Совместимость выбранной ветки Boot с Java/Gradle проверена по
[официальной документации](https://docs.spring.io/spring-boot/3.5/system-requirements.html).

Это не реализация семестрового MVP. Нет регистрации, JWT/refresh, каталога, дневника,
расчётов, seed-каталога или бизнес-API. Первая миграция создаёт только схему `calorica`;
таблицы Liquibase находятся в `public`. Применённые changeset-файлы не изменять.
Следующие миграции добавляют домен и данные с источниками в рамках отдельных задач.

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
`DB_POOL_SIZE` (по умолчанию 5). JSON настроен на UTC; контейнер запускает JVM в UTC.
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
Проверки требуют чистой БД данной версии (не production). `test` отдельно пока
не содержит unit-тестов: для этого каркаса существенны интеграционные проверки.
CI дополнительно собирает контейнер, проверяет запуск и отказ БД:
readiness → 503, liveness → 200. Обновление с предыдущего production-релиза пока
не проверяется: предыдущей серверной версии нет. При следующей миграции добавьте
проверку перехода со старой схемы и запуска предыдущего совместимого образа.

## API и границы

Публичны только GET `/actuator/health`, `/actuator/health/liveness`,
`/actuator/health/readiness`. Детали компонентов скрыты. Readiness включает БД
и параметризованный SQL проверки схемы; liveness не зависит от доступности БД.
Остальные методы и маршруты закрыты через Spring Security `denyAll`.
Нет Basic/form login, cookie-сессий, встроенного пользователя или тестового bypass.
Сессии должны быть реализованы по `docs/architecture/runtime-policies.md` до
открытия защищённых маршрутов.

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
