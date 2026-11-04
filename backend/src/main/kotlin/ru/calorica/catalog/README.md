# catalog — T05/T09

Конечные и вложенные составные базовые/личные продукты, точные ручные КБЖУ,
поиск, PERCENT-составы и выход, KEEP_REFERENCES. Контракт —
`docs/semester-mvp/contracts/v1`, версия 1.0.0. GRAMS/outputGrams остаются T11,
CASCADE — T14; неподдерживаемый запрос завершается ошибкой без частичной записи.
Эта локальная реализация не подтверждает опубликованный API или завершение живой приёмки.

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
  с переданным snapshot. Миграция040 добавляет единую SQL-проекцию catalog_paths:
  UNION ALL сохраняет пути и rootOrdinal каждой порции. Расчёт/чтение истории
  сохраняют удалённые листья. catalog_nutrition обходит узлы снизу вверх и считает
  SUM(w * childNutrition) / Y; деление после суммы сохраняет контрольное 240/0.75=320.
  Распределение готовых граммов использует q, а общий ручной выход q не меняет.
- CatalogRead.getVisible и CatalogCalculation.nutrition также принимают
  CatalogWriteTransaction для материализации ответа до commit. Эти перегрузки
  требуют активный capability текущего соединения и уже захваченные shared base +
  exclusive owner locks именно для actor; они не открывают вложенный snapshot.
  Историческая видимость и единый SQL-расчёт сохранены. Ошибка расчёта откатывает
  вызывающую мутацию. allocateLeaves по-прежнему использует read-only snapshot.
- CatalogMutationLock: personal / base — единый порядок advisory locks по T00,
  не допускающий повышения shared в exclusive либо второго owner в одной транзакции.

Capability связано с физическим Spring ConnectionHolder и инвалидируется после
выхода из scope. Не сохранять его, не передавать в REQUIRES_NEW/другой поток.
Read-only ответы используют REPEATABLE READ; мутации READ COMMITTED и свежие
проверки после ожидания блокировки. SQL timeout 5с, lock timeout 2с, transaction 8с.
Общий HTTP deadline задаёт platform/security T03.

POST products принимает name+composition без фиктивных ручных значений. PUT composition
атомарно заменяет рёбра; при возврате к листу нужны прежние или переданные ручные
КБЖУ. Сохранённые manualNutrition и скрытые коэффициенты не теряются: absent
сохраняет, NULL очищает. Любой non-NULL коэффициент ребра включает INGREDIENTS и
очищает manualYieldFactor; конфликт явных режимов даёт409. Проверки прав, удаления
и цикла выполняются после advisory locks; исключение откатывает и POST, и PUT.

catalog_paths потоково проверяет 100001-ю строку путей и 10001-й уникальный узел
до агрегации; повторный productId в разных порциях входит в общий лимит путей.
Повреждённый цикл возвращает CYCLE_DETECTED, превышение NUMERIC/scale/размера —
CALCULATION_LIMIT, SQL timeout — CALCULATION_TIMEOUT. Частичный результат не выдаётся.
Нет ограниченной глубины вложенности и сохранённых рассчитанных итогов.

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
