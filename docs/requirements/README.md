# Подробные доменные правила семестрового MVP

Объём активной разработки определяет [семестровый комплект](../semester-mvp/README.md).
Функции Android и серверного API перечислены в [границах поставки](../semester-mvp/scope.md),
актуальное ТЗ с архитектурой — в [материалах третьего этапа](../semester-mvp/stage-3/technical-specification.md).
Эта папка уточняет правила обязательного серверного домена, а не расширяет клиентский объём.

## Правила

- [Граф состава продуктов](domain/product-composition-graph.md).
- [Выход готового блюда](domain/cooking-yield.md).
- [Единицы измерения](domain/units-of-measurement.md).
- [Продуктовые лейблы и лейблы приёмов пищи](domain/product-and-meal-labels.md).
- [Доступ и мягкое удаление](domain/access-and-soft-deletion.md).
- [Расчёты и хранение](domain/calculation-principles.md).
- [Начальный базовый каталог](data/base-catalog.md).
- [Числовая точность](../architecture/numeric-precision.md).
- [Сессии, время и состояния клиента](../architecture/runtime-policies.md).

Требования расширенного продукта и журнал прежних решений сохранены в ветке
future/product-development. Для разработки main они не используются.
