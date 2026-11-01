# Инфраструктура Calorica: инструкции агенту

Объём разработки задают `docs/semester-mvp/scope.md` и корневой README.
Инфраструктурный каркас и health endpoints не подтверждают готовность бизнес-API.

Источник подключения — [README.md](README.md), адреса — `infrastructure.json`.
Владелец явно выбрал **единый общий административный доступ** для команды
и её агентов. `.env.infra` содержит API-ключи и SQL-права владельца; файл с правами
600 размещается в корне рабочей копии и исключён из Git.

Из корня:

```sh
python3 scripts/infra/access.py status
python3 scripts/infra/access.py db-tables
python3 scripts/infra/access.py db --sql 'SELECT id, author FROM public.databasechangelog LIMIT 10;'
python3 scripts/infra/access.py api render /services/srv-dbjk97vlk1mc739gja40
python3 scripts/infra/access.py dev-run --debug
python3 scripts/infra/access.py codex mcp get CaloricaRender
```

- MCP `CaloricaNeon`, `CaloricaRender`, `CaloricaLogs` берут ключи из общего профиля
  через локальный `http_headers_helper`. Личный OAuth не нужен. Внутреннюю команду
  `mcp-headers` не вызывать через чат: её stdout предназначен только MCP-клиенту.
- Не выводить профили, токены, пароли или полные connection URLs в чат, argv,
  логи, diff и коммиты. Для API использовать `access.py api`: он скрывает секреты.
  Нужный ответ с секретами сохранять только в новый закрытый `--output-file`.
- `db` выполняет READ ONLY транзакцию; `db-admin` явно фиксирует SQL через COMMIT.
  Запись в основную БД выполнять в рамках порученного изменения. Разработка
  и отладка — в `dev-team` либо отдельной ветке. Не переписывать применённые миграции.
- Общий Render key охватывает весь аккаунт. Соседние сервисы менять только
  по отдельному заданию; проверка Calorica сама по себе такого задания не даёт.
- Не включать платные планы и ресурсы без отдельного запроса. Не ротировать
  SQL-пароль основной ветки без синхронного обновления DB_PASSWORD Render.
- Статусы проверять вручную, не включать polling/auto-refresh для обхода сна.
  После отладки завершать локальный backend и SQL-сессии.
- Интеграционные тесты backend запускать на отдельной чистой одноразовой БД;
  общая основная БД и обычная dev-ветка для них не подходят.
- Документация не требует Gradle. Изменения `scripts/infra` проверять:
  `python3 -m unittest discover -s scripts/infra -p 'test_*.py'`.
