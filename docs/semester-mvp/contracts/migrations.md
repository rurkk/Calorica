# Реестр миграций и общих файлов

Резервация T00, версия 1.0.0. Новые файлы ниже ещё не существуют и не применены.
Текущий master включает только changes/001-foundation.sql (calorica:001-foundation).
Никогда не переписывать применённый SQL. Новые SQL — Liquibase formatted SQL,
схема calorica, история public. Схему бизнес-данных не создавать в public.

| Порядок / файл в changes/ | Changeset author:id | Автор | Зависимость / границы |
| --- | --- | --- | --- |
| 001-foundation.sql | calorica:001-foundation | существующий | схема calorica; неизменяемый |
| 010-accounts-profile.sql | calorica:010-accounts-profile | T03 / агент 1 | только 001; accounts, sessions, refresh hashes, profile |
| 020-products.sql | calorica:020-products | T05 / агент 2 | только 001; products, pg_trgm, индексы и sourced seed |
| 025-products-owner-fk.sql | calorica:025-products-owner-fk | агент 1, интеграция | 010+020; FK products.owner_id→accounts.id |
| 030-diary.sql | calorica:030-diary | T07 / агент 3 | 010+020; diary_entries, meal_labels, diary_entry_labels |
| 040-composition.sql | calorica:040-composition | T09 / агент 2 | 020; product_components и manual_yield_factor |
| 050-units.sql | calorica:050-units | T12 / агент 7 | 020; units, product_units |
| 060-product-labels.sql | calorica:060-product-labels | T13 / агент 7 | 020+040; product_labels, product_label_links |

010 и 020 независимы: products.owner_id nullable UUID, без ранней FK и без
создания accounts-заглушки. T05 использует UUID текущего actor через согласованный
security principal, тестовый actor — только в тестах. T03 не меняет products.
025 добавляется агентом 1 после интеграции обоих; до него пользовательский выпуск
каталога и SA-02 ждут T03 и FK. T03/T05 могут выполнять SQL/build в своих БД параллельно.
Seed базовых продуктов всегда owner_id NULL; не создавать фиктивных пользователей.
Поля FK в diary используют те же UUID. Новое пространство changeset резервировать
здесь до реализации; не забирать свободный номер произвольно.

Агент 1 владеет db.changelog-master.yaml. Для 010 и 020 заранее делегирована только
вставка include своего файла по числовому порядку с relativeToChangelogFile=true;
чужой include не удалять. Это согласование владельца, не передача всего файла.
При merge второй ветки сохранить оба include; зависимости проверять от свежей main.
025 интегрирует агент 1 при готовности обеих миграций отдельным совместимым PR.
Остальные include запрашивать у владельца. Резерв не означает включение отсутствующего
SQL в master. Каждый changeset тестируется на пустой и предыдущей изолированной
PostgreSQL; никакой пользовательской БД. Backend-деплои последовательные.

| Общий файл/контракт | Владелец и потребители |
| --- | --- |
| contracts/v1/**, domain DTO/маршруты, этот реестр | агент 1; изменения согласуются с 2/3/4/7 |
| platform/api/** Problem, RequestId | агент 1 (T03); все серверные задачи |
| platform/security/** и Actor(id UUID, role USER/ADMIN, sessionId UUID) | агент 1 T03; T05/T07 используют principal, не JWT-парсер |
| catalog/application/CatalogRead, CatalogCalculation, CatalogMutationLock | агент 2; агенты 3/7 |
| catalog SQL расчёта и проекция листьев | агент 2; без копий в diary/analytics |
| db/changelog/db.changelog-master.yaml | агент 1; узкая делегация 010/020 выше |
| accounts/**, profile/** и SQL 010 | агент 1 T03 |
| catalog/** products/composition и SQL 020/040 | агент 2 |
| diary/**, analytics/** и SQL 030 | агент 3 |
| catalog/**/units/**, catalog/**/labels/** и SQL 050/060 | агент 7 |
| MainActivity, DI, navigation, API client, app/build.gradle.kts, versions | агент 4 T02; агенты 5/6 через него |
| Android products/composition adapters | агент 5; интерфейсы агента 4 |
| Android diary adapters | агент 6; интерфейсы агента 4 |
| workflows, scripts/ci, deploy, Docker/Compose, окружение | агент 8 T01/T17 |
| tracker.md | каждый владелец только своя строка/секция, merge сохраняет соседей |

Точные пути Android регистрирует T02 в существующем com/rurkk/calorica,
предметные backend-пакеты — ru/calorica/{accounts,profile,catalog,diary,analytics}
со слоями api/application/domain/persistence. Этот документ не создаёт код T03.
