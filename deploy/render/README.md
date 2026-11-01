# Учебный стенд: Render и Neon

Настройка от 1 ноября 2026 года. Приложение остаётся инфраструктурным каркасом:
бизнес-API и пользовательские сессии ещё не реализованы.

Для команды и агентов используйте [единую инструкцию доступа](../access/README.md):
личные read-only учётки общей БД, изолированные dev-ветки и локальная отладка.

## Адреса

| Ресурс | Адрес |
| --- | --- |
| Backend | https://calorica-backend.onrender.com |
| Readiness, включая БД и схему | https://calorica-backend.onrender.com/actuator/health/readiness |
| Liveness процесса | https://calorica-backend.onrender.com/actuator/health/liveness |
| Render Dashboard | https://dashboard.render.com/web/srv-dbjk97vlk1mc739gja40 |
| Neon Console | https://console.neon.tech/app/projects/long-king-45707171/ |

Старый VPS `62.84.122.55` недоступен по результатам проверки 24 октября 2026 года.
Домен `caloricaitmo.duckdns.org` не перенастраивался. Данные старой БД не перенесены.
Старая инструкция VPS сохранена в [deploy/backend](../backend/README.md).

## Neon

- Организация: `Raul`, `org-summer-rice-10673431`, тариф Free.
- Проект: `calorica`, `long-king-45707171`.
- Регион: AWS Frankfurt, `aws-eu-central-1`.
- PostgreSQL: 17; SQL-подключение подтвердило версию 17.11.
- Ветка БД: `main`, `br-old-sky-bawhtlz5`.
- База: `neondb`; роль: `neondb_owner`.
- Compute: 0.25 CU, без увеличения верхней границы; автоматическое засыпание
  по умолчанию Free после пяти минут простоя. API-значение таймаута `0` означает
  использование настройки тарифа.

В рамках Free действуют ограничения Neon, включая 1 ГБ данных и 100 CU-часов
на проект в месяц. Постоянные запросы к БД мешают засыпанию и расходуют квоту.
Пароли и строки подключения с паролями в документацию и Git не включаются.

## Render

| Параметр | Значение |
| --- | --- |
| Name | `calorica-backend` |
| Service ID | `srv-dbjk97vlk1mc739gja40` |
| Repository / branch | `rurkk/Calorica`, `main` |
| Region | Frankfurt |
| Runtime | Docker |
| Root Directory | `backend/` |
| Dockerfile Path | `./Dockerfile` относительно Root Directory |
| Docker Build Context | `.` относительно Root Directory |
| Compute | Free: 0.1 CPU, 512 MB RAM |
| Health Check Path | `/actuator/health/liveness` |
| Auto-Deploy | Off; обновление через Manual Deploy |

HTTPS предоставляет Render; отдельный Nginx и сертификат на VPS не нужны.
Приложение слушает порт 8080, совпадающий с Docker healthcheck. Liveness в Render
не делает запросов к БД; готовность БД и схемы проверяйте отдельным readiness.
Миграции Liquibase выполняются при запуске приложения.

Environment Variables:

| Имя | Значение |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `PORT` | `8080` |
| `DB_POOL_SIZE` | `2` |
| `DB_URL` | JDBC URL прямого endpoint Neon, без логина и пароля |
| `DB_USERNAME` | Роль из Neon Connect |
| `DB_PASSWORD` | Пароль из Neon Connect; только в секретах Render |
| `JAVA_TOOL_OPTIONS` | Строка ниже |

```text
-Xms16m -Xmx96m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC
```

Формат JDBC URL; подставьте Host и Database из Neon Connect:

```text
jdbc:postgresql://<HOST>:5432/<DATABASE>?sslmode=require&channelBinding=require
```

Ожидаемый readiness: HTTP 200 и `{"status":"UP"}`. Корневой URL и закрытые
бизнес-маршруты возвращают 401, пока функции не реализованы. Бесплатный Render
засыпает после 15 минут простоя; первый запрос после сна может ждать около минуты.

## Проверка 1 ноября 2026 года

- Первый выпуск `dep-dbjk987lk1mc739gjavg` из commit `b52dde8` завершился статусом
  Live. Docker-образ собран на Render; приложение запустилось с профилем prod.
- Публичные readiness и liveness возвращают HTTP 200 и `{"status":"UP"}`.
  `/api/products` возвращает HTTP 401 с Problem Details.
- Прямой SQL-запрос подтвердил БД `neondb`, роль `neondb_owner` и PostgreSQL 17.11.
  Клиент psql с `sslmode=verify-full`, системными CA и обязательным channel binding
  установил TLS 1.3. Пароли в команды, логи и Git не выводились.
- Проверены исключение `.env.local` из Git, режим 600, наличие переменных Spring
  и конфигурация OAuth MCP с ограничением проекта. Это настройка MCP, а не
  подтверждение его OAuth-входа в новом сеансе Codex.

## Локальная интеграция Neon с Codex

Установлен официальный Neon CLI 8.16.0, вход выполнен через браузер. В отдельной
рабочей копии настроены навыки `neon`, `neon-postgres` и OAuth MCP, ограниченный
проектом `long-king-45707171`. Файл `.codex/config.toml` не содержит API-ключа;
при первом использовании MCP понадобится вход в Neon. Для загрузки интеграции
откройте эту рабочую копию в Codex и начните новый сеанс.

Приведённая ниже привязка CLI относится к рабочей копии владельца. Разработчикам
нужно использовать личный `.env.infra` и `scripts/infra/access.py dev-run` из
[инструкции команды](../access/README.md), чтобы локальный backend не менял общую БД.
Neon MCP в репозитории ограничен проектом и `readonly=true`; права Viewer
не включают SQL Editor или получение строк подключения.

Локальные `.neon` и `.env.local` исключены из Git. `.env.local` имеет режим 600;
к переменным CLI добавлены `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE`
и `SPRING_PROFILES_ACTIVE`, которые ожидает Spring Boot. Для запуска с JDK 21:

```sh
# Из корня этой рабочей копии; env создан CLI и хранится только локально.
set -a
. ./.env.local
set +a
cd backend
./gradlew bootRun
```

Не запускайте интеграционные тесты против этой БД: им нужна отдельная чистая база.
Повторная привязка существующего проекта без TypeScript-конфигурации:

```sh
neon login
neon init -y --agent codex --skill neon --skill neon-postgres \
  --mcp-auth oauth --mcp-config-location project --mcp-project-scoped \
  --no-config --org-id org-summer-rice-10673431 \
  --project-id long-king-45707171
```

CLI заполняет свои `DATABASE_URL*`; после повторной привязки проверьте переменные
`DB_*` для Spring Boot. `neon.ts` и Node-зависимости в Kotlin-проект не добавлялись.

## Обновления и сохранность данных

Render сейчас обновляется вручную из `main`. Перед включением Auto-Deploy после
успешных CI-проверок нужно отключить прежний SSH-деплой: repository variable
`BACKEND_DEPLOY_ENABLED=false`. Настройки GitHub этой задачей не изменялись.
Для автодеплоя настройте фильтры так, чтобы Markdown и документация не запускали
новую сборку. Старый GitHub workflow не разворачивает приложение на Render.

Регулярные внешние резервные копии и мониторинг ещё не настроены. Free не означает
подтверждённые SLA, RPO или RTO; квоты обоих провайдеров нужно контролировать.

Документация провайдеров: [Render Docker](https://render.com/docs/docker),
[Render Free](https://render.com/docs/free), [Neon CLI init](https://neon.com/docs/cli/init),
[Neon Java](https://neon.com/docs/guides/java), [Neon Free](https://neon.com/pricing).
