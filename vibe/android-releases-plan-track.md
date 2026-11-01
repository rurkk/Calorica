# Ход работы

- [x] Проверены origin/main, worktrees, активные чаты и бронь T01.
- [x] Владелец T01 согласовал помощь и отдельную область файлов.
- [x] Реализованы выпуск, постоянная подпись, версии, повтор, rollback и Summary.
- [x] Интегрирована параллельная main `5e77c017b8d0ca706a86bae5ae03db89ecf4ed39` без конфликтов.
- [x] Владелец разрешил также Android-абзацы README и docs/debugging.md; они обновлены.
- [x] Самостоятельное ревью: проверены исходный SHA, чужой тег, superseded draft,
  неизменность опубликованных assets, корректный Summary после ошибки artifacts.
- [x] Подготовлен постоянный ключ вне Git; secrets в GitHub не изменялись.
- [ ] Публикация/живая приёмка: нужны разрешение владельца на push/PR/merge,
  загрузку signing secrets и реальный запуск выпуска.

## Проверки

- Python: 17 Android regression tests проходят; полный scripts/ci suite — 25 тестов
  (Android + фильтры компонентов + Render), проходят.
- actionlint 1.7.7: `.github/workflows/android.yml`, `release.yml` — без ошибок.
- JDK 21: `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` — BUILD SUCCESSFUL.
  Unit test-классов Android пока нет: `testDebugUnitTest NO-SOURCE`.
- Release APK 0.1.2 (3): `apksigner` подтверждает сертификат;
  `aapt` подтверждает package, версию и отсутствие debuggable.
- CI override 0.1.3 (4): повторная release-сборка и проверка фактической версии/того же сертификата успешны.
- Сертификат SHA-256: `df0cd33806531f682d53e031c9abeaddd41160f973ba75ea16575755f80236f1`.
- APK 0.1.2 SHA-256: `166815c3b068d3a70171f2334251dfaf1b79c3ed845f68d7f7599a8c4a658504`.
- Чтение реальных GitHub Releases/тегов без записи: следующий выпуск — 0.1.2 (3).
- `git diff --check` — без ошибок.
- `adb devices -l` — устройств нет. Установка/обновление не проверены.
- Реальная публикация, permissions/secrets GitHub и живой rollback не проверены.
  Отдельный необязательный ANDROID_RELEASE_TOKEN поддерживает исторические SHA,
  для которых GitHub требует Workflows: write.

Статус Android-части: локально реализовано и проверено; не опубликовано.
Бронь T01 остаётся у владельца render-autodeploy; ручная приёмка не заявляется.
