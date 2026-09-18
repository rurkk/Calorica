# Android quality gates

Read this reference while researching, planning, implementing, testing, or reviewing a Calorica Android feature. Apply only affected sections. `AGENTS.md` and established project contracts take precedence.

## Always

- Trace every `AC-###` to a plan task, implementation evidence, an automated check when feasible, and review status.
- Prefer the smallest reliable test layer and add tests during implementation.
- Do not add logging, mock libraries, destructive migration fallback, or unjustified dependencies.
- When the app exists, run final gates once after the stable diff: `./gradlew :app:testDebugUnitTest`, then `./gradlew :app:assembleDebug`.

## Data and calculations

- Repository/Room remains the source of truth; UI does not access a data source directly.
- Preserve immutable state down and events up. Keep screen state in ViewModels and reusable composables parameter-driven.
- A saved food entry keeps a nutrient snapshot, quantity, unit context, and source semantics sufficient to reproduce its displayed total.
- Goal changes, product-card edits, and template edits must not silently rewrite past diary totals.
- Unit conversion, rounding, empty/unknown food data, date boundaries, duplicate entries, edit/delete/undo, and partially completed input need explicit behavior and relevant tests.
- Keep local operations correct while offline. Future sync must not be smuggled in as an untested side effect.

## Room and sensitive data

- Use handwritten migrations; register them; commit exported schemas; never use `fallbackToDestructiveMigration`.
- Keep entity, DAO, migration, database version, schema JSON, and migration tests under one owner.
- Make multi-write diary operations transactional.
- Minimize personal data and avoid leaking food names, free text, body parameters, tokens, or identifiers into logs, analytics, crash context, or public intents.

## Compose and system integration

- Read `docs/design-system.md`. Cover loading, empty, content, validation, and error states.
- Verify units and meaningful state through labels, not color alone; test semantics where practical, `fontScale = 2.0`, 48dp targets, keyboard/screen-reader use, and alternate actions for gestures or charts.
- Preserve minimal UI state with `rememberSaveable`/`SavedStateHandle`; persist domain state in Room.
- For workers, permissions, external intents, export/import, or network sync, verify denial, cancellation, retry/idempotence, offline/degraded paths, and data disclosure.

## Build and DI

- Use the project’s declared Android toolchain. Do not add Kotlin/Gradle plugins, Hilt scopes, dependencies, or release changes without inspecting current conventions.
- Justify each dependency by user value, Compose/toolchain compatibility, APK/R8 impact, maintenance, and testability.
- For release-sensitive build, manifest, serialization, or dependency changes, run the appropriate release gate when available or report the blocker.
