# Profile / T03

Профиль создаётся атомарно вместе с аккаунтом: все четыре цели NULL.
GET /api/v1/accounts/me возвращает Account.profile, PATCH /api/v1/profile — Profile.
calorieTarget/proteinTarget/fatTarget/carbsTarget: decimal-строки 0..99999.99,
до2 дробных знаков, без exponent/плюса/экранного округления. JSON-число —400,
невалидная decimal-строка —422. Неизвестные поля и подстановка чужого ID —400.
Absent сохраняет колонку, null очищает её, {} ничего не меняет. SQL обновляет только
явно переданные колонки, account_id всегда берётся из серверного Actor.
Публичного profile/{чужойID} и обязательного Android-экрана не добавляется.
