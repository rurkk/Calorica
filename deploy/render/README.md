# Учебный стенд: Render и Neon

Настройка от 1 ноября 2026 года. Приложение остаётся инфраструктурным каркасом:
бизнес-API и пользовательские сессии ещё не реализованы.

Для команды и агентов используйте [единую инструкцию доступа](../access/README.md):
общий административный набор, SQL/API/MCP и локальная отладка на dev-team.

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
| Source | Existing Image: `ghcr.io/rurkk/calorica-backend@sha256:…` |
| Region | Frankfurt |
| Runtime | Image; образ linux/amd64 собирается в GitHub Actions |
| Compute | Free: 0.1 CPU, 512 MB RAM |
| Health Check Path | `/actuator/health/liveness` |
| Auto-Deploy | Off: доставкой конкретного digest управляет GitHub Actions |

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
- Проверены исключение env-профилей из Git, режим 600 и наличие переменных Spring.
  Общие ключи проверены через API и MCP: SQL Neon, сервис/логи Render и запрос
  runtime-логов Better Stack. MCP-конфигурация загружается в новом доверенном сеансе.

## Общий доступ команды и агентов

По запросу владельца используется один общий административный `.env.infra`:
API-ключ организации Neon, ключ владельца аккаунта Render, глобальный Read & write
ключ Better Stack, SQL-владелец основной БД и отдельный пароль `dev-team`.
Пакет хранится вне Git и одинаков для всех уполномоченных участников.

Команды подключения, MCP `CaloricaNeon` / `CaloricaRender` / `CaloricaLogs`,
права веб-панелей и проверка доступа собраны в [единой инструкции](../access/README.md).
MCP использует локальные credential helpers, читающие `.env.infra`; личный OAuth
для общего набора не требуется. Нужны доверие проекту и новый сеанс Codex.

Ранее созданная `.env.local` владельца и `.neon` по-прежнему исключены из Git.
Для локального backend используйте `scripts/infra/access.py dev-run`:
он подключает общую dev-ветку отдельным паролем и не выбирает основной endpoint.
SQL-пароль основной ветки в общем пакете совпадает с `DB_PASSWORD` Render;
его ротация требует согласованного обновления приложения и пакета команды.

## Обновления и сохранность данных

Действующий путь: **GitHub Actions → GHCR → Render**. После серверных изменений
в `main` существующий Backend CI публикует образ linux/amd64, затем job `deploy`
передаёт Render точный `ghcr.io/rurkk/calorica-backend@sha256:…`. Render скачивает
готовый образ и запускает его; исходники на Render повторно не собираются.
Liquibase применяет миграции при запуске. Адрес API и переменные окружения сохраняются.

Доставкой управляет GitHub Actions; `autoDeployTrigger=off` отключает независимые
Git-сборки Render. Существующие проверки GitHub сохранены; новые проверки
приложения или режим After CI Checks Pass в Render этим изменением не настраиваются.

Фильтрацией компонентов управляет `.github/workflows/backend.yml`:
документация и Android не выпускают backend. Изменения только workflow проверяют
инфраструктуру, без продуктового релиза. Деплои сериализованы группой
`calorica-render-deploy`; устаревшая автоматическая ревизия пропускается.

### Настройки доставки

- Существующий сервис `srv-dbjk97vlk1mc739gja40`: **Settings → Build → Source →
  Existing Image**, репозиторий образов `ghcr.io/rurkk/calorica-backend`.
- Образ публичный: пароль GHCR в Render не нужен. Образы используемых выпусков
  сохраняйте в GHCR: Render повторно загружает их при развёртывании и перезапуске.
- В GitHub environment **backend-production** хранится `RENDER_API_KEY`.
  Caller `backend.yml` явно передаёт его в `backend-release.yml`, где он объявлен
  обязательным входным secret. Job `deploy` выбирает environment и получает его
  значение; без передачи caller environment secret разрешается в пустую строку.
  Это существующий административный ключ Render; значение в Git не входит.
  ID сервиса и ссылки берутся из `deploy/access/infrastructure.json`.
- Скрипт `scripts/ci/render_deploy.py` допускает только Calorica и digest,
  запускает деплой через Render API и до 10 минут ждёт `Live`, затем до
  90 секунд ожидает публичный readiness `200` с JSON `status=UP`.
  Публичному endpoint не передаётся административный ключ. Ошибка API,
  неудачный deploy, неготовая БД или таймаут завершают workflow с ошибкой.
- После успешных Live и readiness тот же digest сохраняется как Image URL сервиса,
  чтобы последующие ручные запуски использовали эту версию. В итогах workflow —
  digest, SHA workflow и ссылки на deploy, API и runtime-логи.
- Workflow **Render deploy existing image** запускается вручную из `main`:
  в поле `image` передайте полный существующий `ghcr.io/rurkk/calorica-backend@sha256:…`.
  Это повтор доставки либо откат на образ, совместимый с текущей схемой БД;
  схема и данные автоматически не откатываются. До запуска нужна функциональная
  [проверка совместимости T17](../backend/PERSISTENCE.md) на текущих предметных
  данных: один readiness не подтверждает безопасный возврат. SHA workflow — версия скрипта,
  источник кода самого образа смотрите в OCI label `org.opencontainers.image.revision`.

SSH-job удалён из `.github/workflows/backend-release.yml`; старые переменные
`BACKEND_DEPLOY_*` и секреты `BACKEND_SSH_*` удалены из GitHub. Compose и скрипт
VPS сохранены для локальных проверок и как историческая конфигурация.

Статус конкретной версии: **Render Dashboard → Events**, там же доступны логи
запуска и ссылка на образ. `Live` подтверждает запуск; deploy-workflow отдельно
ждёт готовность БД и схемы через readiness. Проверки бизнес-сценариев этим не заменяются.
Для временной остановки автоматической доставки отключите job `deploy`
в backend release workflow через PR; флаг Auto-Deploy в Render уже выключен
и сам по себе не блокирует API-запуски из GitHub Actions.

Регулярные внешние резервные копии и мониторинг ещё не настроены. Free не означает
подтверждённые SLA, RPO или RTO; квоты обоих провайдеров нужно контролировать.

Документация провайдеров: [Render Images](https://render.com/docs/deploying-an-image),
[Render Free](https://render.com/docs/free), [Neon CLI init](https://neon.com/docs/cli/init),
[Neon Java](https://neon.com/docs/guides/java), [Neon Free](https://neon.com/pricing).
