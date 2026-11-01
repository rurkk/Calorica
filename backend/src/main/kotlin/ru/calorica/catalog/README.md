# catalog — T05

Конечные базовые и личные продукты, точные ручные КБЖУ на 100 г, поиск и
KEEP_REFERENCES. Контракт — `docs/semester-mvp/contracts/v1`, версия 1.0.0.
Граф/выход/каскад/единицы/лейблы добавляются соответствующими последующими задачами.
Не реализованные composition/CASCADE запросы не сохраняют частичный результат.

Слои: HTTP/строгий разбор в api, транзакции и права в application, decimal и типы
в domain, параметризованный SQL в persistence. Единственная таблица products
создаётся 020-products; 50 seed записей имеют источник USDA/FDC в SQL-комментариях.
Корневой include 020 добавлен по узкой делегации T00. FK025 интегрирует агент1/T03.

Публичные операции других серверных модулей:

- CatalogRead.getVisible: весь запрошенный набор доступен либо 404; историческое
  чтение включает мягко удалённые продукты, чужие личные строки исключаются всегда.
- CatalogRead.requireSelectable: внутри CatalogTransactions.mutation, с его tx.
  Берёт shared base + exclusive owner и затем отдельным statement читает доступность.
- CatalogCalculation.nutrition / allocateLeaves: внутри CatalogTransactions.snapshot
  с переданным snapshot. Проекция конечных листьев исполняется SQL; T09 расширяет
  тот же интерфейс рекурсией. Расчёт/чтение истории сохраняют удалённые листья.
- CatalogMutationLock: personal / base — единый порядок advisory locks по T00,
  не допускающий повышения shared в exclusive либо второго owner в одной транзакции.

Capability связано с физическим Spring ConnectionHolder и инвалидируется после
выхода из scope. Не сохранять его, не передавать в REQUIRES_NEW/другой поток.
Read-only ответы используют REPEATABLE READ; мутации READ COMMITTED и свежие
проверки после ожидания блокировки. SQL timeout 5с, lock timeout 2с, transaction 8с.
Общий HTTP deadline задаёт platform/security T03.

Поиск нормализует имя и q одной SQL-функцией; lower/ё→е/удаление ъь/пробелы.
Порядок exact, prefix, trigram >=0.3; внутри similarity DESC/name COLLATE C/UUID.
Для длины<3 только literal prefix. Cursor — непрозрачный keyset с fingerprint
пользователя и нормализованного q; это пагинация, не авторизация. Каждый SQL
заново фильтрует owner/is_deleted. После изменений каталог загружается с начала.

Проверки T05: CatalogValidationTest, CatalogMigrationIntegrationTest и
CatalogIntegrationTest. Последний использует настоящие сессии/JWT T03 и HTTP,
проверяет чужие ID/Admin, PATCH, поисковый порядок, cursor, историю и конкурирующие
PostgreSQL транзакции. Интеграционные тесты требуют одноразовой PostgreSQL, не
main/dev-team; запуск `./gradlew --no-daemon check bootJar` с JDK21 и DB_*.
Результаты точных прогонов и опубликованного выпуска фиксируются в tracker.
