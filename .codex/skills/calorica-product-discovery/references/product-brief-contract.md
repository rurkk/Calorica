# Product brief contract

Read when finalizing an agreed Calorica product decision.

## Artifact rules

- Write the smallest standalone Russian document useful to the owner, designer, and future implementer.
- Use `PD-###` for decisions and `AC-###` for observable acceptance criteria.
- Label claims as `Факт`, `Вывод`, or `Допущение`; external facts receive direct links and dates.
- Describe user-visible behavior and domain meaning, not database or Compose implementation.
- Do not claim user validation, clinical validity, or analytics evidence that does not exist.

## Suggested sections

```markdown
# <Название фичи>

Статус: согласовано / черновик
Обновлено: YYYY-MM-DD

## Решение
## Проблема и контекст
## Доказательства и допущения
## Рассмотренные варианты
## Продуктовое поведение
## Семантика данных и история
## Scope
## Acceptance criteria
## Проверка ценности и guardrails
## Риски и компромиссы
## Открытые вопросы
## Журнал решений
```

Where relevant, define entry, creation/edit/delete/undo, units and rounding, empty/error/offline behavior, history after food/goal changes, data export/deletion, privacy, and accessibility. Historical food entries must remain intelligible from their saved nutrient snapshots. Keep the product independent; do not make external integration an implied requirement.
