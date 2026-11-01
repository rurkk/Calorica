# Схемы Calorica в draw.io

| Схема | Редактируемый исходник | Экспорт |
| --- | --- | --- |
| C4 Context | [context.drawio](context.drawio) | [PNG](../assets/context.drawio.png) |
| C4 Container | [containers.drawio](containers.drawio) | [PNG](../assets/containers.drawio.png) |
| Компоненты сервера | [server-components.drawio](server-components.drawio) | [PNG](../assets/server-components.drawio.png) |
| БД: аккаунты и каталог | [data-model.drawio](data-model.drawio) | [PNG](../assets/data-model.drawio.png) |
| БД: дневник и метки | [data-model-diary.drawio](data-model-diary.drawio) | [PNG](../assets/data-model-diary.drawio.png) |
| Android | [android.drawio](android.drawio) | [PNG](../assets/android.drawio.png) |
| Развёртывание | [deployment.drawio](deployment.drawio) | [PNG](../assets/deployment.drawio.png) |

Все исходники и PNG с внедрённым XML открываются для редактирования в draw.io.
В ТЗ и презентации используются одинаковые экспорты.

Два ER-вида описывают одну PostgreSQL: 12 отдельных таблиц без повторений.
Каждое поле занимает строку, справа указаны PK, FK <table.field> и уникальность.
Между видами таблицы связаны внешними ключами, указанными у соответствующих полей.

Android и PostgreSQL находятся вне границы серверного контейнера.
На связях указаны протоколы и SYNC/ASYNC. Поясняющие блоки и легенды удалены.

Сборка и экспорт описаны в [README этапа](../README.md).
