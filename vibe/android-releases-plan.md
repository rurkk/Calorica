# Android: выпуск и восстановление

Область согласована с владельцем T01: бронь
`4e3004500cf72f5db4abf7f7fe4db0963efe4be2`, чат Android
`01a1980a-73e7-7322-930c-7d77a497f548`, ветка `codex/android-releases`.
Backend workflows, README, deploy и tracker меняет владелец T01.

1. Автоматический выпуск после Android CI на main; docs/backend не выпускают APK.
2. Версия резервируется draft-релизом; повтор сохраняет версию, опубликованные assets неизменны.
3. Постоянный ключ из secrets, проверка сертификата и фактических APK version/package.
4. Ручной повтор и rollback к опубликованному SHA с новым versionCode;
   проверка API/local-data epoch и подтверждение оператора.
5. Итоги со ссылками на APK, артефакты, SHA, workflow и стенд/логи.
6. Python regression tests, actionlint, unit tests/debug/release сборка;
   публикация и настройка секретов только после отдельного разрешения.
