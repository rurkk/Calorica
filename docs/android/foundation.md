# Android foundation 1.0.0 — T02

Контракт: `docs/semester-mvp/contracts/v1`, версия 1.0.0, интегрированная T00
`db7ed5f49c6de0eaf76d394869254312c604aa34`. Это общий вход для T04/T06/T08,
а не реализация регистрации, каталога, дневника или расчёта КБЖУ.

## Границы и подключение

- Агент 4: `com.rurkk.calorica.core/**`, `MainActivity`, общий manifest,
  `app/build.gradle.kts` и каталог версий. Release workflows/scripts остаются у T01.
- Агент 5: будущий `features/products/**`, экраны/состояния/адаптеры продуктов.
- Агент 6: будущий `features/diary/**`, экраны/состояния/адаптеры дневника.
- T04: будущий `features/auth/**`; восстановление сессии, Keystore и logout.
  Корневые auth/products/diary маршруты интегрируются через агента 4.

Hilt предоставляет единственные `AuthRepository`, `ProductRepository`,
`DiaryRepository`, `ConnectionRepository`, `CaloricaApi`, `OkHttpClient` и
`InMemorySession`. ViewModel внедряет интерфейс репозитория, Compose получает
состояние/обработчики. HTTP, токены и JSON не создаются внутри экранов.

`FeatureScreens` — composable slots Auth/Products/Diary/ProductPicker.
Владелец экрана передаёт свою реализацию; агент 4 подключает её в корне.
`FoundationScreens` лишь показывает подготовленные точки, без фальшивого входа,
каталога, дневных нулей или выбранного продукта. Навигационные кнопки текущей
основы доступны для проверки маршрутов; T04 подключит session gate к реальному
экрану входа. Они не означают успешную авторизацию.

Общий выбор: экран вызывает `picker.launch(ProductSelectionRequest.create(DIARY))`
либо COMPOSITION. Picker возвращает `ProductSelection(productId)` через callback.
`picker.result` приходит только вызывающему back stack entry и содержит requestId,
purpose и UUID продукта. После обработки вызвать `picker.consume()`; отмена не
создаёт результата. Навигация передаёт ID, без копии карточки/нутриентов и без
зависимости дневника от UI каталога. Выбор продукта реализуется в T06.

## Данные и запросы

Все DTO находятся в `core/api/Models.kt`, интерфейсы в `core/data/Repositories.kt`.
`ApiResult.Success` и `Failure` различаются явно. ReadController получает scope
ViewModel, предикат пустоты и suspend чтение. Перед отрисовкой используется
StateFlow/collectAsStateWithLifecycle. `close()` при уходе отменяет чтение и
сбрасывает состояние; отмена не превращается в сетевую ошибку. `Empty` сохраняет
серверный ответ (например, итог пустого дня), а ошибка никогда не создаёт пустой список.
При повторном открытии экрана выполнить новое чтение. Постоянного зеркала/очереди нет.

Decimal — BigDecimal из JSON-строки с точкой без exponent/plus. Сериализация
сохраняет decimal-строку и разряды. Только преобразование доля↔процент предусмотрено
через movePointRight/Left, без расчётов КБЖУ. Выходные значения не ограничиваются
scale входных полей; длина ограничена контрактным вычислительным пределом.
`PatchField.Unchanged/Set/Clear` сохраняет absent/value/null; Clear запрещён
для обязательных полей. Коэффициенты, отсутствующие в форме, остаются Unchanged.
ProductCreate.Composite не требует выдуманных ручных КБЖУ. DiaryPatch требует
совместного изменения quantity/unit; [] явно очищает пищевые лейблы.

Базовый адрес: HTTPS origin `https://calorica-backend.onrender.com/`.
Другой HTTPS origin задаётся `-PcaloricaApiBaseUrl=https://example.test/`.
Незащищённый HTTP, credentials, query/fragment и path prefix не допускаются в DI.
`allowHttp` у фабрики используется только из изолированных MockWebServer-тестов.
В manifest запрещён cleartext. UI «Связь с сервером» вызывает настоящий readiness
вне `/api/v1`; он не утверждает готовность бизнес-API. Fixtures не включены в APK.

Запрос ограничен общим coroutine deadline 10 секунд и OkHttp call/connect/read/write
таймаутами 10 секунд. Redirects и retryOnConnectionFailure отключены. Не повторяются
автоматически ни записи, ни чтения. Кнопка повтора чтения блокируется при загрузке.
Будущие формы должны блокировать повтор сохранения до окончания своего запроса.
При сетевом/неопределённом результате записи показывать `error.writeMessage()` и
перечитать данные перед новой попыткой, не заявлять, что запись не сохранилась.

Ошибки: известные Problem.code и field codes переводятся на русский; неизвестные
получают безопасную общую подпись. detail/HTML/стек не выводятся. UUID correlationId
можно показать для обращения; Retry-After доступен потребителю. Логирующих HTTP
interceptors нет; credentials/tokens не выводят секреты через toString.

## Сессия для T04

Access token держится только в памяти и прикрепляется к запросам единственным
interceptor. `InMemorySession.replaceAccessToken/clear` — точки подключения.
HTTP AuthRepository возвращает Tokens, но сам не объявляет вход/выход и не хранит
refresh token. Explicit refresh сериализуется mutex, автоматического refresh нет.
T04 должен добавить single-flight orchestration: захватывать актуальный refresh
после входа в координацию, обновлять его атомарно и не отправлять заранее захваченный
старый refresh вторым caller. При неоднозначной ротации необходим повторный вход.
Keystore, восстановление, очистка пользовательских состояний и session gate — T04.

## Проверки и ручной сценарий

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME="$ANDROID_HOME" \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Unit-тесты используют непосредственно frozen fixtures T00 из test resources,
не копию контракта. MockWebServer исполняет реальные HTTP-запросы на loopback:
отсутствие ответа 10 секунд, отмена, разрыв после записи, redirects, malformed JSON,
Problem, session header и маршруты. Эти проверки не заменяют живой сервер.

На опубликованном подписанном APK:

1. Сверить release.json source SHA, версию, SHA-256 и сертификат. Установить/обновить
   APK; проверить запуск на API 26 и согласованном современном эмуляторе.
2. Открыть Дневник → Выбрать продукт → Отмена, затем Продукты, системный Назад.
   Это проверка маршрутов, продукт не выбирается фиктивно.
3. Связь с сервером → Проверить соединение: зафиксировать реальный результат
   readiness, его адрес и SHA APK. UP подтверждает readiness, не auth/products/diary.
4. На отдельном тестовом устройстве отключить Wi-Fi/мобильную сеть. Повторить
   загрузку: ошибка видна, пустые данные не появляются, загрузка конечна.
   Восстановить сеть и повторить вручную. После начала запроса уйти назад: чтение отменяется.
5. Использовать ADB/UIAutomator/accessibility tree; скриншоты и видео не требуются.

Точный итог тестов, ревью, merge, релиза и устройств публикуется в tracker.md.
Ручное подтверждение человека обязательно для «Готово»; отсутствие физического
устройства или живого API указывается отдельно, а не заменяется фейком.
