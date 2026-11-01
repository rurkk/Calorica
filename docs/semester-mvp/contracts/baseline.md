# Аудит исходного состояния T00

Проверка 1 ноября 2026. Исходная main `40386c1da532c944d19669da9ae175a98316237f`.
Изолированный clone origin, незакоммиченные изменения общей копии не переносились.
Это инвентаризация исходников и read-only схемы, не прогон всей приёмки.

| Область | Факт / доказательство | Что ещё не подтверждено |
| --- | --- | --- |
| Android | MainActivity.kt показывает Text("Calorica"), Compose/Hilt каркас | API client, auth, каталог, дневник, навигация SM-01–09 отсутствуют |
| Backend | Spring Boot/JDBC/Liquibase, platform/api RequestId и Problem Details, denyAll кроме health | accounts/catalog/diary/analytics пока README; бизнес-API и сессии отсутствуют |
| SQL в Git | master включает calorica:001-foundation; SQL создаёт только схему calorica | предметные таблицы и seed отсутствуют |
| SQL действующего main Neon | access.py db: READ ONLY SELECT id,author FROM public.databasechangelog и pg_tables WHERE schemaname='calorica'; ROLLBACK. Одна запись 001-foundation/calorica, 0 предметных таблиц | данные/функции будущей схемы не проверены |
| Автотесты | FoundationIntegrationTest: health, deny fake credentials, revalidate/update Liquibase | тесты функций отсутствуют; повторный Gradle в T00 не запускался |
| CI/CD | backend.yml фильтрует docs, независимый Android workflow, Render image по digest | выпуск текущего T01 и его живые проверки ведёт владелец T01 |
| Исторические проверки | backend/VERIFICATION.md содержит датированные проверки foundation и старого VPS | их нельзя переносить на новый backend/Neon или считать готовностью MVP |

Команда SQL (без секретов):

```sh
python3 scripts/infra/access.py --env-file /Users/raul/ItmoProjects/Calorica/.env.infra db \
  --sql "SELECT id, author FROM public.databasechangelog ORDER BY orderexecuted; SELECT tablename FROM pg_tables WHERE schemaname='calorica' ORDER BY tablename;"
```

Источник адресов и работы стенда — deploy/render/README.md. T00 не обращался
к пользовательской БД с записью, не менял сервисы, не запускал Gradle, ADB,
эмулятор или продуктовый релиз. Координатор сообщил 1 ноября о Render deploy Live,
но публичные health вернули read timeout; это переданное наблюдение, не собственный
успешный HTTP-прогон T00. Readiness и точный выпущенный digest остаются проверками
T01/владельцев выпуска. API management и SQL-доступ не доказывают живую готовность.

Решение SA-17: по ответу владельца «Давай максимально просто. Это студенческий
проект пока что» обязательный критерий — перезапуск сервера и БД с сохранением
подтверждённых данных. Acceptance/ТЗ/T17 приведены к этому; существующие исторические
доказательства backup не удаляются и не считаются обязательным новым требованием.
Существующие эксплуатационные скрипты и их защиты от неуспешного backup не меняются.

[API 1.0.0](v1/README.md) и [миграционный реестр](migrations.md) задают проверяемые
входы T02/T03/T05. Фейки не являются реализованными endpoint. Ручная приёмка T00
отделена от технической готовности опубликованного контракта.
