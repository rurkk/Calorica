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
