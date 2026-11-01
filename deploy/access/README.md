# Доступ к инфраструктуре Calorica

Единая инструкция для разработчиков и агентов. Состояние на 1 ноября 2026 года.
Адреса без секретов также собраны в [infrastructure.json](infrastructure.json).
Пароли передаются каждому участнику отдельным файлом `.env.infra`.

## Что доступно

| Задача | Способ | Права |
| --- | --- | --- |
| Проверить состояние backend | Публичные health endpoints или команда `status` | Без входа |
| Посмотреть данные общей БД | Личная SQL-учётка `calorica_ro_*`, psql или IDE | Чтение таблиц `calorica` и истории Liquibase |
| Менять схему и данные, отлаживать backend | Личная ветка Neon `dev-*` и локальный запуск JVM | Полные права внутри своей ветки БД |
| Посмотреть проект в Neon Console | Личное приглашение в организацию Raul | Viewer: метаданные проекта; SQL-доступ предоставлен отдельно |
| Посмотреть runtime-логи | Better Stack, после принятия приглашения Member | Поток подключён; приглашения отправлены |
| Деплой, настройки Render, его build-логи | Владелец Render | Коллегам не выдаётся ключ владельца |

На Render Hobby доступен один участник workspace; приглашение команды требует
платного тарифа. Free web service также не предоставляет SSH/shell.
Поэтому командная отладка выполняется локально с личной БД, а runtime-логи
доступны через бесплатный Better Stack.
[Ограничения Render](https://render.com/docs/team-members),
[Free web services](https://render.com/docs/free).

Backend сейчас содержит инфраструктурный каркас. Таблиц предметной области пока
нет, а бизнес-маршруты закрыты: HTTP 401 на `/api/products` ожидаем.

## Адреса

| Ресурс | Ссылка |
| --- | --- |
| API | [calorica-backend.onrender.com](https://calorica-backend.onrender.com) |
| Liveness — процесс | [health/liveness](https://calorica-backend.onrender.com/actuator/health/liveness) |
| Readiness — приложение, БД и схема | [health/readiness](https://calorica-backend.onrender.com/actuator/health/readiness) |
| Render, для владельца | [Dashboard](https://dashboard.render.com/web/srv-dbjk97vlk1mc739gja40) · [Логи](https://dashboard.render.com/web/srv-dbjk97vlk1mc739gja40/logs) |
| Neon | [Проект calorica](https://console.neon.tech/app/projects/long-king-45707171/) |
| Runtime-логи | [Better Stack Live tail](https://telemetry.betterstack.com/team/t627028/tail?s=l2819108) — источник `Calorica Render logs`, ID `2819108` |
| Исходники и CI | [GitHub](https://github.com/rurkk/Calorica) · [Actions](https://github.com/rurkk/Calorica/actions) |

Общая БД: `neondb`, PostgreSQL 17, ветка `main` (`br-old-sky-bawhtlz5`).
Host: `ep-morning-grass-barfhgst.c-8.eu-central-1.aws.neon.tech`, порт `5432`.
Проект Neon: `long-king-45707171`, организация `org-summer-rice-10673431`.
Оба сервиса размещены во Франкфурте. Старый VPS и адрес DuckDNS больше
не используются для подключения к рабочему стенду.

## Первое подключение участника

1. Принять личные приглашения Neon и Better Stack на ту почту, которую передали владельцу.
2. Клонировать репозиторий. Получить от владельца **свой** пакет доступа,
   скопировать `.env.infra` в корень рабочей копии, выполнить `chmod 600 .env.infra`.
3. Установить Python 3.9+ и PostgreSQL client `psql` 17+. На macOS: `brew install libpq`;
   скрипт умеет находить стандартный путь Homebrew на Apple Silicon.
4. Из корня репозитория проверить доступ:

```sh
python3 scripts/infra/access.py status
python3 scripts/infra/access.py db-tables
python3 scripts/infra/access.py db --sql 'SELECT id, author, dateexecuted FROM public.databasechangelog LIMIT 10;'
```

`status` не требует пароля. Render Free засыпает после 15 минут без запросов;
холодный запуск может занять около минуты. При таймауте повторите проверку вручную.
Не настраивайте периодический ping ради предотвращения сна: он расходует
бесплатные ресурсы Render и Neon. [Поведение Free](https://render.com/docs/free).

Путь к профилю можно задать явно:

```sh
python3 scripts/infra/access.py --env-file /absolute/private/path/.env.infra db-tables
python3 scripts/infra/access.py db --file /absolute/path/query.sql
```

`db` принимает SQL. Обратная косая черта запрещена, в том числе внутри строк:
это исключает команды оболочки и файловые операции psql. При необходимости
получить такой символ в SQL используйте `chr(92)`.

Скрипт не исполняет содержимое env как shell-код и не передаёт пароль аргументом
процесса. Профиль исключён из Git. Не прикладывайте его к issue, PR или сообщению
агенту; достаточно передать агенту путь к локальному файлу.

### Подключение из IDE или SQL-клиента

Для общей БД укажите Host, Port, Database, User и Password из блока `PG*`
своего профиля. Выберите TLS с проверкой сертификата и имени сервера (`verify-full`)
и системное доверенное хранилище CA; для libpq также `channel_binding=require`.
Не используйте `neondb_owner` для общей БД.

SQL-учётки имеют SELECT на текущие таблицы `calorica` и новые таблицы, созданные
в этой схеме ролью `neondb_owner`; на `public` доступна только история и lock
Liquibase. Запись в постоянные таблицы и создание объектов схемы запрещены
правами PostgreSQL. Таймаут одного запроса — 5 секунд, лимит — 3 соединения
на пользователя. Закрывайте неиспользуемые консоли.

Neon Viewer показывает метаданные, но не даёт строк подключения или SQL Editor.
Права Viewer и персональное подключение PostgreSQL — разные уровни доступа.
[Роли Neon](https://neon.com/docs/manage/user-permissions),
[права БД](https://neon.com/docs/manage/database-access).

## Локальная отладка и изменения данных

Созданы отдельные ветки `dev-kamila` и `dev-raul`. Каждому участнику передаётся
пароль владельца **его ветки**, отличный от пароля основной БД.
Ветки изолированы: изменения схемы и строк не переносятся в `main` автоматически.
Миграции переносятся через код и ревью, после чего применяются при деплое.

Требуется JDK 21. Команда использует `DEV_*` из личного профиля, включает профиль
Spring `prod` и ограничивает пул двумя соединениями:

```sh
python3 scripts/infra/access.py dev-run
# Запуск с ожиданием подключения Java debugger на локальном порту 5005:
python3 scripts/infra/access.py dev-run --debug
```

Backend слушает `http://localhost:8080`. В IDE создайте Remote JVM Debug на порту
5005; остановка — Ctrl+C. Общий endpoint Neon скрипт для `dev-run` отклоняет.
Пароль личной dev-ветки проверен: он не подходит к основной ветке.

Для SQL-изменений в своей ветке используйте Host из `DEV_DB_URL`, базу `neondb`,
`DEV_DB_USERNAME` и `DEV_DB_PASSWORD`. Не подключайте миграционные/интеграционные
тесты к общей БД. `backend/gradlew check` требует отдельную чистую одноразовую БД;
обычная dev-ветка с уже применёнными миграциями для такого теста не подходит.
Подробности локального Docker и тестов — в [backend/README.md](../../backend/README.md).

Ветки созданы как снимки `main` на момент выдачи доступа. Новые миграции из Git
Liquibase применит при следующем запуске локального backend. Сброс/удаление ветки
уничтожает её собственные изменения; такие операции согласуйте с владельцем.

## Runtime-логи

Better Stack создан на Free: 3 ГБ логов, хранение 3 дня. Источник `Calorica Render logs`
создан, приглашения двух участников отправлены с бесплатной ролью Member.
Передача логов Render включена и проверена настоящим HTTP-запросом. На Free регион логов — United States
(`us-west-2a`); смена региона в интерфейсе помечена как платная и не выполнялась.
Не считайте отсутствие событий в Better Stack доказательством исправности backend.
[Тариф Better Stack](https://betterstack.com/pricing).

На Render Hobby поток логов задаётся для всего workspace. В текущем workspace
есть Calorica и ещё один приостановленный учебный сервис. Владелец подтвердил
передачу логов обоих сервисов; при запуске второго его логи также будут доступны
участникам Better Stack. Исключение сервиса из общего потока на Hobby недоступно.
[Механизм Log Streams](https://render.com/docs/log-streams).

Откройте Live tail и выберите источник `Calorica Render logs`. Это источник
workspace; события Calorica отмечены `syslog.host: calorica-backend`.
Для поиска
конкретного HTTP-запроса используйте `correlationId` из ответа/Problem Details
или заголовок `X-Request-Id`; access-лог содержит тот же идентификатор, метод,
статус и длительность. Введите ID в двойных кавычках в поисковую строку и нажмите
Enter. Не добавляйте в сообщения тела запросов, пароли или токены.
Build/deploy-логи остаются в Render у владельца; runtime-поток их не заменяет.

## Доступ агентов

Для агента, работающего локально в репозитории, доступны те же команды
`scripts/infra/access.py`. Достаточно Python и личного профиля с правами 600.
Запрос агенту может выглядеть так:

> Проверь статус Calorica и последние миграции. Используй scripts/infra/access.py
> и мой локальный .env.infra. Не выводи секреты; общую БД только читай.
> Изменения и запуск backend выполняй на моей dev-ветке.

В [.codex/config.toml](../../.codex/config.toml) подключён Neon OAuth MCP,
ограниченный проектом Calorica и `readonly=true`. В доверенной рабочей копии
начните новый сеанс Codex; вход CLI из корня:

```sh
codex mcp login Neon
```

Это вход в личный аккаунт Neon. MCP использует его платформенные права: Viewer
не получает SQL только за счёт наличия MCP. У Viewer SQL работает через личный
профиль и Python/psql, а MCP — в пределах доступных метаданных.
Ограничение `readonly=true` не запрещает другие действия владельца через CLI
или Console; агенты не должны использовать owner-профиль вместо персонального.
[Neon MCP](https://neon.com/docs/ai/neon-mcp-server),
[настройка Codex MCP](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

Для Better Stack в том же config подключён OAuth MCP `https://mcp.betterstack.com`
со scope `read`. Каждый участник входит своим аккаунтом после принятия приглашения:

```sh
codex mcp login BetterStack --scopes read
```

При выборе разрешений оставьте Read-only. Для владельца OAuth-вход успешно
выполнен с запросом scope `read`; сервер публикует scopes `read` и `write` в
[OAuth metadata](https://mcp.betterstack.com/.well-known/oauth-protected-resource).
В текущем чате новые MCP-инструменты не загружаются: начать новый сеанс нужно
после сохранения конфигурации. Реальные runtime-события проверены в Live tail;
вызов MCP-инструмента чтения логов в новом сеансе отдельно ещё не выполнялся.

Если `codex mcp get BetterStack` сообщает, что сервер не найден, откройте именно
рабочую копию с этим config и разрешите доверие проекту в Codex: конфигурация
`.codex/config.toml` загружается только для доверенных проектов. Не добавляйте
owner API key в качестве обхода. Общие ключи Render/Neon/Better Stack не раздаются.
[Better Stack MCP](https://betterstack.com/docs/getting-started/integrations/mcp/).

## Квоты и эксплуатация

- Render Free: 0.1 CPU / 512 MB, heap JVM 16–96 MiB; обновление вручную из `main`.
- Neon Free: по 0.25 CU на endpoint, автосон по настройке Free (5 минут).
  Все dev-ветки и основная БД делят **100 CU-часов в месяц на проект** и лимит
  данных 1 ГБ; создание веток не даёт отдельную месячную квоту.
  [Neon Free](https://neon.com/pricing).
- Останавливайте локальный backend после работы: его пул может поддерживать
  соединения с dev-веткой и мешать сну. Не держите SQL-клиент с автообновлением.
- Платные тарифы, карты, responder licenses и мониторинг постоянными запросами
  для этой схемы не требуются и не включались.
- Регулярные внешние резервные копии и автоматические оповещения пока не настроены.

## Памятка владельцу

Приглашение Neon не отправляет пароль SQL. Каждому участнику передайте только
его пакет `.env.infra` + `PERSONAL-SETUP.md` по личному каналу. Пакеты не размещать
в репозитории, GitHub Releases или общедоступном облаке. Не пересылайте admin
`.env.local`, из которого работает основная БД/Render.

[readonly.sql](readonly.sql) содержит воспроизводимые общие SELECT-grants без
паролей. Новому участнику дополнительно нужны отдельный LOGIN, членство в
`calorica_observer`, личный пароль и dev-ветка с собственным паролем владельца.
Если таблицы создаёт другая роль, настройте default privileges для этой роли
отдельно; текущая настройка распространяется на `neondb_owner`.

При отзыве доступа отключите личный SQL LOGIN в `main`, завершите его активные
соединения, отзовите приглашение/членство Neon и Better Stack. Отдельно смените
пароль владельца личной dev-ветки или удалите её после согласования сохранности
данных. Удаление человека из Neon не отзывает его пароль PostgreSQL.

Проверка инфраструктурных инструментов без Android/Gradle:

```sh
python3 -m unittest discover -s scripts/infra -p 'test_*.py'
```

Проверено: оба личных SELECT, запрет постоянных DDL/DML в основной БД даже при
снятии session read-only, транзакционные CREATE/INSERT/ALTER в личных ветках
с последующим ROLLBACK, отказ dev-паролей при попытке входа в основную БД.
Публичные liveness/readiness вернули HTTP 200/UP. Контрольный GET закрытого
`/api/products` вернул ожидаемый 401, а его `X-Request-Id` найден в Better Stack
в записи `RequestIdFilter` с тем же статусом.
