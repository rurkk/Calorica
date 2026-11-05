# PR43: исправления ревью, 4 ноября 2026

Координатор NiKLaYDM, чат `01a1a741-a242-7242-a7fc-fe8c5b499415`.
Исходный PR43: `f428b3244cc0b1144779e37f8ddee2094c1e665a`.
Проверенный код исправлений: `10f675d` (последующие правки этого отчёта — документационные).

| Дефект | Исправление | Доказательство |
| --- | --- | --- |
| D-PR43-01 / T07: вычисление ответа после commit | Ответ POST/PATCH материализуется внутри write-транзакции; ошибки расчёта дают 422 и откат записи/лейблов | HTTP-регрессии create и patch: до исправления 503 с изменением БД, после 422 без частичной записи; capability/locks tests |
| D-PR43-02 / T04: потеря session ViewModelStore при rotation | Retained session holder; очистка по account/generation/logout, включая background | Три Robolectric/Compose теста с Activity recreation, draft, pending request, picker и сменой аккаунта; прежний dispose-clear воспроизводимо ломает регрессию |
| D-PR43-03 / T08: устаревший пояс | Текущий пояс при resume; UTC черновиков и неопределённых create/patch/delete сохраняется; ack проверяет date+zone ответа | Десять новых unit-сценариев; независимый probe завершённого TIMEOUT → смена пояса сначала падал, после follow-up проходит |
| D-PR43-04 / T06,T08: сырые десятичные подписи | HALF_UP только при показе: карточка/БЖУ до 2, калории дневника целые, масса до 3 | Пять тестов formatter, точности API и round-trip формы; исходные данные не округляются |

## Проверки общей версии

- Android JDK21: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — успешно;
  90 tests, 0 failures/errors/skipped; lint 0 errors/fatal, 13 прежних warnings.
- Backend JDK21/PostgreSQL17.11: `./gradlew -p backend check bootJar` — успешно;
  20 unit + 64 integration, 0 failures/errors/skipped. Отдельная локальная БД.
- `git diff --check` — успешно.
- Отдельный headless Android36 AVD `Calorica_QA_01a1a741`, настоящий MainActivity/Hilt,
  QA debug APK с отдельным package и доверенным только debug локальным CA:
  вход через HTTPS API, draft продукта после portrait/landscape/portrait,
  Moscow → Honolulu через pause/resume, восстановление Keystore-сессии после
  force-stop/relaunch — успешно. Crash buffer без падения приложения.
  Эмулятор и QA backend после проверки остановлены; пользовательский стенд не запускался.
- Независимое ревью: T07 — root (область агента2); T04 — fix_diary_timezone (агент6);
  T08 — fix_nutrition_format (агент5, включая повторное ревью follow-up);
  T06 — fix_session_scope (агент4). Блокирующих замечаний не осталось.

## Ограничения и следующий шаг

Это проверка исправлений PR43, а не завершение всего MVP. CI GitHub проверяется после
публикации. Слияние в main и выпуск на общий стенд требуют отдельного подтверждения
пользователя; сейчас не выполнены. Брони T04/T06/T07/T08 сохранены до полного цикла.
Пользователь сообщил, что физический Android пока недоступен; эта часть T16 остаётся
непроверенной. Следующие карточки выполняются отдельными сабагентами по зависимостям.
