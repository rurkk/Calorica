# Accounts / T03

Контракт 1.0.0: docs/semester-mvp/contracts/v1. POST /api/v1/accounts/register
(201), login/refresh (200), logout (204); GET me (Account с nullable profile).
Запросы credentials содержат только email/password; роль назначается сервером USER.
Email trim/lower-case, уникальный; пароль 8–128 Unicode codepoints, без trim.
Argon2id m=19456 KiB,t=2,p=1, индивидуальная соль; одновременное хеширование ограничено.
Пароли и токены не включены в toString DTO, SQL/HTTP ошибки и access logs.

Сессия абсолютная 30 дней. Access HS256 JWT до15 минут с issuer/audience/sub/sid;
каждый защищённый запрос перечитывает активную сессию и роль из PostgreSQL.
Refresh — случайные256 бит; в БД только SHA-256. Ротация под FOR UPDATE сессии,
использованные хеши не удаляются. Replay фиксирует отзыв ДО возврата401; logout
отзывает только текущую сессию. 010 независима от продуктов020, FK025 интегрируется отдельно.

AUTH_JWT_SECRET необязателен: base64 ключ минимум32 байта, только environment secrets.
Если не задан, генерируется криптостойкий ключ процесса; после перезапуска старый
access отклоняется, сохранённая сессия восстанавливается её refresh. Это рассчитано
на один инстанс учебного Render Free. Для нескольких инстансов нужны общий ключ
и общее хранилище rate limits; масштабирование этой задачей не вводится.

Register/login используют общий bucket10/мин/IP, refresh30/мин/IP; 429 + Retry-After.
Лимиты процесса фиксированными60s окнами, память ограничена10000 активными buckets;
при заполнении отказ, существующие buckets не вытесняются. Локально XFF игнорируется.
При автоматически заданном Render RENDER=true доверие начинается только с private
socket-peer; XFF ограничен4096 байт/32 адресами, читается справа налево через private
и опубликованные CIDR Cloudflare до первого недоверенного literal IP. DNS не используется,
IPv6 канонизируется. Списки CIDR проверены1 ноября2026; при изменении топологии
их нужно пересмотреть, неизвестный hop становится границей доверия.
Источники: [Render client IP](https://render.com/articles/how-render-handles-ddos-attacks),
[RENDER env](https://render.com/docs/environment-variables),
[Cloudflare IPv4](https://www.cloudflare.com/ips-v4), [IPv6](https://www.cloudflare.com/ips-v6).

Deadline8s включает неблокирующее чтение тела (до1MiB, включая chunked) и приложение.
После чтения используется контейнерный async dispatch, максимум4 активных обработчика;
перегрузка503. Ответ буферизуется, timeout возвращает503 с Content-Length, поздний
handler не меняет его headers/body. Активный request не завершается до выхода handler,
что не допускает его преждевременного recycle. Таймер прерывает handler; транзакция,
уже просроченная перед commit, откатывается. SQL statement_timeout5s, lock_timeout2s.
Timeout в момент уже начавшегося JDBC commit может иметь неопределённый исход:
как требует контракт, автоматически повторять запись нельзя; нужно перечитать результат.
Прерывание не является принудительным уничтожением произвольного Java-кода.

Слои api/application/domain/persistence; другие модули используют Actor(id,role,sessionId)
из platform/security и @AuthenticationPrincipal. JWT самостоятельно не разбирают;
Admin не получает обход проверки владельца личного объекта.
