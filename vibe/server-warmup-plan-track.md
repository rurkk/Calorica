# Ход работы

- [x] Проверены main, T01/T02, существующие HTTP deadline и deploy-script.
- [x] Реализовано ограниченное ожидание readiness в Android.
- [x] Реализован публичный readiness после Render Live.
- [x] Проверены deadline, retry, отмена, строгий UP и отсутствие повторов записи.
- [x] Пройдены 32 Android unit-теста, debug/release сборки и lint; 37 Python-тестов CI.
- [x] Новый readiness probe на настоящем сервере вернул UP.
- [x] На собственном AVD Android36 UIAutomator видит ожидание без сети и ошибку с кнопкой после deadline; crash log пуст.
- [x] После восстановления сети retry открыл экран входа; переход в каталог и возврат из фона сохранили экран. AndroidRuntime:E пуст.
- [x] [PR #21](https://github.com/rurkk/Calorica/pull/21) прошёл проверки и слит после явного «да» пользователя: `8d44b35f0bebff45786ce17232dd68389d88fc8b`.
- [x] [Android CI и автоматический выпуск](https://github.com/rurkk/Calorica/actions/runs/41339180010) и [Backend CI](https://github.com/rurkk/Calorica/actions/runs/41339180004) завершились успешно.
- [x] Опубликован [подписанный APK 0.1.4](https://github.com/rurkk/Calorica/releases/tag/android-v0.1.4), versionCode 5, source SHA совпадает с merge SHA.
- [x] Скачанный APK проверен: постоянный сертификат `df0cd33806531f682d53e031c9abeaddd41160f973ba75ea16575755f80236f1`, SHA256 `d7ed2b2f1e1ffff02cbe9f376010a776f379677efb0799ea6f8148e3c8b786f0`; контрольная сумма совпадает с release.json/SHA256SUMS.
- [x] Выпущенный APK обновил 0.1.3 без удаления приложения на отдельном AVD Android 36. UIAutomator подтвердил ожидание без сети, ошибку и «Повторить» после deadline, успешный вход в навигацию после восстановления сети, сохранение каталога при возврате из фона; AndroidRuntime без FATAL EXCEPTION.
- [x] После merge реальный публичный readiness вернул UP через новый wait_for_readiness(). Новый шаг подключён к обоим Render deploy-workflow через общий скрипт; тесты проверяют Live→readiness→сохранение digest и отказ при неготовности.

Backend-код в PR #21 не изменялся, поэтому Backend CI штатно пропустил выпуск
образа и деплой. Полный новый Render deploy после этой правки отдельно не
запускался: с владельцем T05 согласовано проверить его ближайшим выпуском,
чтобы не пересекать backend-развёртывания. Это не утверждение о уже пройденном
полном production deploy нового скрипта. T01 ранее принят и закрыт; его строка
и чужие задачи этой доработкой не менялись.

Ключ подписи и данные пользователя не изменялись. Скриншоты не создавались.

Самостоятельное ревью: readiness допускает только строгий UP; повторы ограничены
общим deadline, уход в фон отменяет настоящий HTTP call; устаревший результат
не меняет новое состояние. В CI ключ Render используется только на API Render,
публичный readiness вызывается без Authorization, неготовый digest не сохраняется.
