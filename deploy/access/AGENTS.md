# Инфраструктура Calorica: инструкции агенту

Объём семестровой разработки определяют `docs/semester-mvp/scope.md` и документы,
на которые ссылается корневой README. Backend и Android сейчас содержат каркасы;
не считать проект реализованным только из-за доступного health endpoint.

## Инфраструктура

Единая инструкция: [README.md](README.md).
Адреса: `deploy/access/infrastructure.json`. Команды из корня репозитория:

```sh
python3 scripts/infra/access.py status
python3 scripts/infra/access.py db-tables
python3 scripts/infra/access.py db --sql 'SELECT id, author FROM public.databasechangelog LIMIT 10;'
python3 scripts/infra/access.py dev-run --debug
```

- Личный профиль `.env.infra` предоставляет владелец; файл игнорируется Git,
  права — 600. Не выводить его содержимое, пароли, токены или owner connection URLs
  в чат, команды с паролем в argv, логи, diff и коммиты.
- Общая БД `main` используется только для чтения через личный `calorica_ro_*`.
  Миграции, DDL/DML и отладка backend — на личной `dev-*` ветке с отдельным паролем.
  Не заменять личный доступ найденными admin credentials или CLI-сессией владельца.
- SQL-запросы ограничивать по времени и объёму; передавать в ответ только данные,
  необходимые для задачи. Постоянные health-ping, polling и автообновление SQL
  расходуют бесплатную квоту; статус проверять по запросу, вручную.
- Не включать платные планы, responder licenses или дополнительные ресурсы
  без отдельного запроса. Не менять owner-пароль основной БД: он используется Render.
- Не запускать интеграционные тесты против общей БД или обычной dev-ветки;
  для них нужна отдельная чистая одноразовая БД, см. `backend/README.md`.
- Для изменения только этой документации не запускать Gradle. Изменения
  `scripts/infra` проверять `python3 -m unittest discover -s scripts/infra -p 'test_*.py'`.
