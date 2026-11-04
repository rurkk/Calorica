# Локальные Android-проверки P2, 3 ноября 2026

Работа выполняется только в локальных ветках по явному решению пользователя:
без общей брони, push, PR, слияния в main и деплоя. Это исключение текущего сеанса,
не изменение командного workflow для остальных участников.

## JVM-проверка настоящих исходников и существующих тестов

Результат: **71 тест, 0 failures, 0 errors, 0 skipped**.

| Класс | Проверок |
| --- | --- |
| ApiNetworkTest | 14 |
| ContractTest | 6 |
| DiaryViewModelTest | 14 |
| NavigationTest | 1 |
| ProductsFeatureTest | 12 |
| ReadControllerTest | 3 |
| ServerWarmupTest | 9 |
| SessionCoordinatorTest | 12 |

Временный Gradle JVM harness расположен в
`/private/tmp/calorica-p2-d14678b2/android-jvm-harness`. Он подключает реальные
исходники `core/api`, `core/data`, session coordinator/repositories/binding,
ReadState/ServerWarmup/ProductSelection, AuthViewModel, продуктовые контроллеры/
ViewModel и DiaryViewModel. Все существующие `app/src/test` и frozen fixtures
подключены напрямую, без копирования или подмены проверяемой реализации.

Версии сохранены: Gradle 9.6.0, Kotlin/serialization plugin 2.4.10,
coroutines 1.10.2, serialization JSON 1.9.0, lifecycle 2.11.0, Retrofit 3.0.0,
OkHttp/MockWebServer 4.12.0, JUnit 4.13.2. Для Jetpack использован настоящий
опубликованный desktop JVM variant, заявленный Google Maven module metadata.
Аннотация HiltViewModel взята из оригинального classes.jar официального
hilt-android 2.60.1 AAR. Поддельных Android SDK/stub-классов нет; исходная
Gradle-сборка приложения и версии её зависимостей не менялись.

Команда из локального checkout:

```sh
./gradlew --no-daemon \
  -p /private/tmp/calorica-p2-d14678b2/android-jvm-harness test
```

HTML-отчёт: `android-jvm-harness/build/reports/tests/test/index.html`;
JUnit XML: `android-jvm-harness/build/test-results/test/TEST-*.xml`.

Проверены в том числе реальные HTTP-запросы на MockWebServer: immutable token
binding при задержанном запросе старого аккаунта через logout/login, отсутствие
автоматического повтора записи, таймауты и отмена. State-тесты проверяют
single-flight refresh, crash-safe забывание старого refresh до запроса,
защиту от позднего 401, конкурентный поиск/пагинацию, скрытые PATCH-поля,
неопределённые записи и обязательное успешное перечитывание, race выбора продукта,
отмену GET при уходе с дневника и сохранение единственного уже отправленного write.

Первый прогон выявил несовпадение старого Class-based factory с общим API
Jetpack JVM variant; фабрики переведены на общий create(KClass, CreationExtras).
Также исправлены тестовые fake handlers, имена которых приводили к рекурсивному
вызову override вместо lambda. Проверяемые критерии не ослаблялись; после правок
выполнен полный успешный прогон всех 71 теста.

## Что эта проверка не подтверждает

Это проверка JVM-совместимой логики, а не Android APK. Не компилировались Compose
экраны, KeystoreRefreshTokenStore, MainActivity и Hilt-generated DI. Не выполнены
полная Android-сборка, lint, запуск/перезапуск процесса на API26/современном
устройстве, проверка Android Keystore и signed APK с живым backend.

Стандартный Android Gradle-прогон остановился на `SDK location not found`.
Установка SDK ожидает решения пользователя по Android SDK License Agreement.
Записи «Готово», выпуск и живая приёмка на основании одного JVM-прогона недопустимы.
