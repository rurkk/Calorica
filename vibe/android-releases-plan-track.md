# Ход работы

- [x] Проверены origin/main, worktrees, активные чаты и бронь T01.
- [x] Владелец T01 согласовал помощь и отдельную область файлов.
- [x] Реализованы выпуск, постоянная подпись, версии, повтор, rollback и Summary.
- [x] Android-изменения и обновлённые правила опубликованы через PR #3.
- [x] PR #4 исправляет ссылку draft → published и сохраняет диагностику APK.
- [x] PR #7 поддерживает фактический формат сертификатов Android SDK Build Tools 37.
- [x] Четыре signing secrets и certificate pin добавлены через Chrome.
- [x] Опубликован и проверен подписанный APK 0.1.2 (3).
- [x] Проверен ручной повтор опубликованного выпуска без изменения assets.
- [x] Проверены установка публичного APK и обновление на отдельном эмуляторе.

## Интеграция и выпуск

- Реализация: https://github.com/rurkk/Calorica/pull/3,
  https://github.com/rurkk/Calorica/pull/4,
  https://github.com/rurkk/Calorica/pull/7.
- Исходный SHA APK: `40386c1da532c944d19669da9ae175a98316237f`.
- Исправление SDK37 в main: `27f8732113a9def18364ae9ac9acc04a120162e4`.
- Успешный выпуск: https://github.com/rurkk/Calorica/actions/runs/41332502598.
- Release: https://github.com/rurkk/Calorica/releases/tag/android-v0.1.2.
- APK: https://github.com/rurkk/Calorica/releases/download/android-v0.1.2/calorica.apk.
- Успешный published retry: https://github.com/rurkk/Calorica/actions/runs/41332724452.
- Сертификат SHA-256: `df0cd33806531f682d53e031c9abeaddd41160f973ba75ea16575755f80236f1`.
- Публичный APK SHA-256: `26326ac774ba78c9e1b14bde8b243ff04ed1c2af21b2cc850e2853810a8af615`.

## Проверки

- Python: 22 Android regression tests; полный scripts/ci suite — 30 тестов,
  все проходят. actionlint 1.7.7 — без ошибок.
- JDK21: `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` — успешно
  локально и в GitHub. Unit test-классов Android пока нет: `NO-SOURCE`.
- `apksigner` и `aapt` подтверждают сертификат, package, версию и отсутствие debuggable
  у скачанного публичного APK. Его SHA совпадает с release.json и SHA256SUMS.
- Ручной retry после неудачи сохранил резерв 0.1.2 (3) и исходный SHA.
  Ручной retry опубликованного выпуска пропустил сборку/публикацию;
  ID/digest/size/updated_at всех assets до и после совпали.
- На отдельном временном эмуляторе Android36: установка публичного APK 0.1.2,
  запуск Activity (Status: ok), UIAutomator подтверждает видимый текст Calorica;
  обновление до локального 0.1.3 (4) с тем же ключом успешно, firstInstallTime
  сохранён, AndroidRuntime:E пуст. Пользовательские устройства не использовались.
- В Chrome проверен Summary успешного retry: APK, метаданные, Release,
  workflow/logs, workflow SHA, source SHA, readiness и серверные логи.
- `git diff --check` — без ошибок.

## Найденный и исправленный дефект

Первые два живых запуска корректно сохранили draft и отклонили публикацию.
SDK37 выводит `V2 Signer: certificate SHA-256 digest`, прежний regex ожидал
`Signer #N`. Фактический сертификат совпадал pin; ключ и secrets не менялись.
Новый parser принимает оба формата и SDK ranges, требует совпадения каждого
сертификата и не принимает Source Stamp за подпись приложения.

## Приёмка и закрытие

1 ноября 2026 Рауль явно подтвердил в этом чате: **«по T01 подтверждаю результат»**.
Владелец T01 согласовал фиксацию приёмки и закрытие; после публикации записи
снимает свою бронь `4e3004500cf72f5db4abf7f7fe4db0963efe4be2`. Принятая доставка
T01 завершена. Отдельная новая доработка пробуждения сервера в это закрытие не входит.

На момент проверки первого релиза 0.1.2 живой rollback не выпускался: более старого
совместимого релиза этого pipeline тогда не было. Проверены политики совместимости,
подтверждения, монотонность версии и отказ от legacy/debug/draft/неизвестной цели;
фиктивные релизы не создавались. Наличие обязательного резервирования БД не заявляется:
оно исключено решением T00, сохранность при перезапуске проверяется в T17.

Подтверждение T01 не является согласием на слияние отдельного PR #18 с общими правилами.
