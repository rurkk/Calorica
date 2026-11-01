# Общий доступ к инфраструктуре Calorica

Единая инструкция для разработчиков и агентов. С 1 ноября 2026 года команда
использует **один общий административный набор** `.env.infra`, выбранный владельцем.
Каждому участнику передаётся одинаковый закрытый архив. Пароли и API-ключи
в репозиторий не входят; адреса находятся в [infrastructure.json](infrastructure.json).

Начало работы с задачами и готовый промпт —
[docs/team-onboarding.md](../../docs/team-onboarding.md).
Для бронирования и push дополнительно нужен личный GitHub-доступ на запись
и настроенная Git-аутентификация: в общем архиве GitHub-токена нет.

## Что входит в набор

| Ресурс | Общие права через SQL / API / MCP | Вход в веб-панель |
| --- | --- | --- |
| Neon / PostgreSQL | Организационный API-ключ и SQL `neondb_owner`: чтение, запись, DDL, миграции, управление ресурсами проекта | Личные приглашения Admin |
| Render | API-ключ владельца аккаунта: сервисы, конфигурация, статусы, деплои и логи в рамках доступных API | На Hobby один участник; дополнительные места требуют оплаты |
| Better Stack | Глобальный API-ключ Read & write: источники, запросы логов и другие доступные API-операции | Бесплатные личные приглашения Member для работы с Telemetry |
| Локальная отладка | Общая ветка Neon `dev-team`, отдельный пароль владельца ветки, запуск JVM и debugger | SQL-клиент или IDE |
| GitHub | Исходники и статусы/логи CI доступны в публичном репозитории | Личный аккаунт GitHub для собственных действий |

Общий API-доступ работает у разработчиков и агентов без личного OAuth.
У веб-панелей сохраняется модель ролей провайдеров: ключ API не является паролем
для входа на сайт. В Better Stack персональные Admin/Responder помечены Billable,
поэтому участники приглашены как Member, а административные API-операции доступны
через общий ключ. Платные тарифы и лицензии этой настройкой не включались.
[Render team members](https://render.com/docs/team-members),
[Render API keys](https://api-docs.render.com/reference/authentication),
[Better Stack pricing](https://betterstack.com/pricing).

Организационный ключ Neon администрирует ресурсы организации, но некоторые
операции с участниками, приглашениями и переносом проектов требуют личного
ключа Admin. Для них используйте личный вход по приглашению Admin.
[Матрица прав Neon API](https://neon.com/docs/manage/orgs-api).

## Адреса

| Ресурс | Ссылка |
| --- | --- |
| API | [calorica-backend.onrender.com](https://calorica-backend.onrender.com) |
| Liveness процесса | [health/liveness](https://calorica-backend.onrender.com/actuator/health/liveness) |
| Readiness приложения, БД и схемы | [health/readiness](https://calorica-backend.onrender.com/actuator/health/readiness) |
| Render | [Dashboard](https://dashboard.render.com/web/srv-dbjk97vlk1mc739gja40) |
| Neon | [Проект calorica](https://console.neon.tech/app/projects/long-king-45707171/) |
| Runtime-логи | [Better Stack Live tail](https://telemetry.betterstack.com/team/t627028/tail?s=l2819108) |
| Исходники и CI | [GitHub](https://github.com/rurkk/Calorica) · [Actions](https://github.com/rurkk/Calorica/actions) |

Neon: организация `org-summer-rice-10673431`, проект `long-king-45707171`,
основная ветка `br-old-sky-bawhtlz5`, БД `neondb`, PostgreSQL 17.
Host: `ep-morning-grass-barfhgst.c-8.eu-central-1.aws.neon.tech`, порт `5432`.
Render: `srv-dbjk97vlk1mc739gja40`, workspace `tea-d67200p5pdvs73c2u8n0`.
Backend и БД находятся во Франкфурте; старый VPS/DuckDNS для работы не используются.

## Первое подключение

1. Получить от владельца общий архив `calorica-team-admin.zip` по закрытому каналу.
2. Получить актуальный `main` репозитория. Скопировать `.env.infra` из архива
   в корень рабочей копии и выполнить `chmod 600 .env.infra`.
3. Установить Python 3.9+ и клиент `psql` 17+. На Apple Silicon достаточно
   `brew install libpq`: скрипт находит стандартный путь Homebrew.
4. Для веб-панелей принять личные приглашения Neon Admin и Better Stack Member.
5. Проверить доступ из корня репозитория:

```sh
python3 scripts/infra/access.py status
python3 scripts/infra/access.py db-tables
python3 scripts/infra/access.py db --sql 'SELECT current_user, id, author FROM public.databasechangelog LIMIT 10;'
python3 scripts/infra/access.py api neon /projects/long-king-45707171
python3 scripts/infra/access.py api render /services/srv-dbjk97vlk1mc739gja40
python3 scripts/infra/access.py api betterstack /sources
```

Путь к профилю можно задать параметром перед командой:

```sh
python3 scripts/infra/access.py --env-file /absolute/private/path/.env.infra db-tables
```

Скрипт не исполняет env как shell-код. API-ключи идут в HTTP-заголовках,
SQL-пароль — в окружении дочернего процесса; секреты не передаются в argv.
Вывод API скрывает пароли, токены и значения секретных env-переменных.
HTTP-перенаправления API отключены, чтобы не пересылать ключи другому адресу.

## SQL и миграции

`PG*` в общем профиле указывают на основную БД с ролью владельца. Она имеет
права на изменение данных и схемы. Для просмотра команда `db` открывает
READ ONLY транзакцию и завершает её ROLLBACK. Для записи используйте `db-admin`:

```sh
# Проверка административного подключения без изменения данных:
python3 scripts/infra/access.py db-admin --sql 'SELECT current_user;'
# Выполнение подготовленного SQL с COMMIT:
python3 scripts/infra/access.py db-admin --file /absolute/path/change.sql
```

`db` и `db-admin` принимают SQL; обратная косая черта запрещена также внутри строк,
чтобы исключить команды оболочки и файловые операции psql. Для символа используйте
`chr(92)`. В IDE укажите `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`
из общего профиля; TLS с проверкой сертификата и имени сервера (`verify-full`).
У libpq также используются системные CA и `channel_binding=require`.

SQL-пароль основной ветки используется развёрнутым backend. При его ротации
обновите общий пакет и `DB_PASSWORD` в Render согласованно. Эта настройка доступа
не меняет рабочий пароль приложения.

Миграции разрабатывайте в dev-ветке и переносите через Git/ревью. Применённые
changesets не переписывайте. Интеграционные тесты backend требуют отдельной
чистой одноразовой БД, см. [backend/README.md](../../backend/README.md).

## Локальный backend и debugger

Общий профиль содержит `DEV_*` для `dev-team`. Пароль этой ветки отличается
от основной БД. Для запуска нужны JDK 21 и доступ к Neon; Docker локально
для такого запуска не требуется:

```sh
python3 scripts/infra/access.py dev-run
python3 scripts/infra/access.py dev-run --debug
```

HTTP: `http://localhost:8080`. В IDE подключите Java debugger к локальному порту
5005; с `--debug` JVM ожидает debugger. Пул БД ограничен двумя соединениями.
Остановка — Ctrl+C. При параллельной разработке согласуйте изменения общей dev-ветки
или создайте отдельные ветки средствами Neon API/MCP.

## Агенты: MCP и CLI

В [.codex/config.toml](../../.codex/config.toml) настроены три сервера:

- `CaloricaNeon` — проект Neon, SQL и операции с БД/ветками;
- `CaloricaRender` — инфраструктура и отладка сервиса Render;
- `CaloricaLogs` — Better Stack, источники и запросы runtime-логов.

Каждый сервер получает свой ключ через локальный `http_headers_helper`, который
читает `.env.infra` в корне этой рабочей копии. Личный OAuth для общего набора
не требуется. В Codex откройте доверенный проект Calorica **из корня репозитория**
и начните новый сеанс после копирования профиля.

Для запуска CLI-агента с готовыми настройками трёх MCP-серверов:

```sh
python3 scripts/infra/access.py codex
```

Команда передаёт Codex настройки явно, а ключи — через окружение. Она работает
также когда CLI ещё не загрузил проектный config. Проверка конфигурации без
запуска диалога с агентом:

```sh
python3 scripts/infra/access.py codex mcp get CaloricaNeon
python3 scripts/infra/access.py codex mcp get CaloricaRender
python3 scripts/infra/access.py codex mcp get CaloricaLogs
```

`mcp-headers` — внутренний канал передачи Authorization в MCP-клиент.
Не запускайте эту служебную команду вручную в чате и не публикуйте её stdout.
Если доступен только терминал, агент использует те же команды `access.py api`,
`db`, `db-admin`, `status` и `dev-run`. Локальные MCP helper-команды не применяются
к агенту, который работает на удалённом облачном исполнителе без этого файла.
Для такого исполнителя ключи надо отдельно поместить в его закрытые секреты.
[Codex MCP](https://learn.chatgpt.com/docs/extend/mcp?surface=cli),
[формат helper](https://learn.chatgpt.com/codex/config-file/config-reference),
[Neon MCP](https://neon.com/docs/ai/neon-mcp-server),
[Render MCP](https://render.com/docs/mcp-server),
[Better Stack MCP](https://betterstack.com/docs/getting-started/integrations/mcp/).

Пример задания агенту:

> Используй общий .env.infra через scripts/infra/access.py или MCP CaloricaNeon,
> CaloricaRender, CaloricaLogs. Проверь readiness, состояние сервиса, последние
> миграции и runtime-логи. Секреты не выводи. Разработку и отладку веди на dev-team;
> запись в основную БД выполняй только в рамках порученного изменения.

CLI с ключами из общего профиля можно запустить так:

```sh
python3 scripts/infra/access.py run -- neon projects list --org-id org-summer-rice-10673431
```

При API-изменениях используйте `--method POST|PATCH|PUT|DELETE` и JSON-файл тела
через `--data-file`. Если ответ содержит нужные новые секреты, сохраните его
через `--output-file /absolute/private/new-file.json`: создаётся новый файл
с правами 600, stdout содержит только HTTP-статус. Общий ключ Render даёт доступ
ко всему аккаунту, в том числе к старому приостановленному сервису; наличие
такого права не является заданием изменять соседние проекты.

## Логи, статусы и бесплатные ресурсы

В Better Stack подключён источник `Calorica Render logs`, ID `2819108`.
Это общий поток Render workspace, включающий также старый приостановленный сервис
при его запуске, что подтверждено владельцем. События Calorica помечены
`syslog.host: calorica-backend`. Для конкретного запроса ищите в Live tail
`X-Request-Id` / `correlationId` из HTTP-ответа, заключив ID в двойные кавычки.
Настоящий запрос с HTTP 401 и тем же request ID проверен в потоке.

- Render Free: 0.1 CPU / 512 MB, heap JVM 16–96 MiB, автосон после 15 минут;
  холодный старт может занять около минуты. Обновления — Manual Deploy из `main`.
- Neon Free: 0.25 CU, автосон по настройке Free (5 минут). Основная БД и dev-ветки
  делят 100 CU-часов на проект в месяц и лимит данных 1 ГБ.
- Better Stack Free: 3 ГБ логов, хранение 3 дня; регион логов United States
  (`us-west-2a`), выбор другого региона в UI платный.
- Статус проверяйте вручную. Постоянный ping и SQL auto-refresh расходуют квоты.
  После отладки останавливайте локальный backend и закрывайте SQL-сессии.
- Бизнес-API ещё не реализован; 401 на `/api/products` ожидаем. Регулярные внешние
  резервные копии и автоматические оповещения пока не настроены.

[Render Free](https://render.com/docs/free), [Neon Free](https://neon.com/pricing).

## Хранение и смена общего набора

Передавайте один и тот же закрытый архив только участникам команды. Не размещайте
его в Git, GitHub Releases, issue или общедоступной ссылке. Агенту достаточно
пути к `.env.infra`; не вставляйте значения в промпт. Если нужно отозвать общий
доступ, ротируйте общие ключи и SQL-пароль, затем раздайте обновлённый пакет
оставшимся участникам. Ротация SQL-пароля требует обновления Render.

Ранее созданные персональные read-only учётки и dev-ветки сохраняются как прежний
способ подключения. Новый общий пакет заменяет их для текущей командной работы.
[readonly.sql](readonly.sql) относится к прежней схеме SELECT-grants.

Проверки инструментов без Android/Gradle:

```sh
python3 -m unittest discover -s scripts/infra -p 'test_*.py'
```

Проверка 1 ноября 2026 года: общие ключи вернули HTTP 200 у трёх провайдеров;
через MCP выполнены SQL Neon, `list_services` и `list_logs` Render, запрос
`count()` по реальным логам Better Stack. Подтверждены SQL-запись и DDL транзакцией
с откатом и отказ пароля dev-team при подключении к основной ветке.
