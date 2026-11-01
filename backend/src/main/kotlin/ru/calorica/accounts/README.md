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

Перед `ACCEPTING_TRAFFIC` выполняется `AuthInitialization`: реальные codecs
Credentials/Refresh/Tokens/ProblemDetail (включая фактические MVC converters и
metadata контроллеров), email validator, три фиксированных цикла Argon2 encode/matches
и JWT issue/verify инициализируются на данных в памяти. Аккаунты/сессии не создаются;
синтетический JWT не имеет сессии и не проходит проверку `activeActor`.
Поскольку HTTP-порт и liveness доступны раньше окончания ApplicationRunner,
`AuthenticationFilter` отвечает503 на `/api/v1/` до готовности приложения,
не расходуя auth bucket и не выполняя SQL/хеширование пользовательского запроса.
Deadline8s и параметры Argon2 остаются прежними.

Регрессия первого auth HTTP-запроса в **новом процессе** с отдельной одноразовой БД:

```sh
# Из корня репозитория; до этого не вызывать auth endpoints в этом процессе.
python3 backend/scripts/cold_auth_smoke.py --url http://127.0.0.1:18035
```

Сценарий допускает только loopback, проверяет readiness, первый register201,
login/refresh/me/profile, replay/revocation и logout; каждый HTTP-ответ должен
уложиться в8s. Мутации не повторяются, в выводе только method/path/status/time/ID.
Для проверки CPU после readiness ограничить только свой контейнер до0.1CPU,
heap96MiB, memory512MiB; повторять на свежих процессах. Квоту при startup записывать
отдельно: startup1CPU→requests0.1CPU не доказывает запуск под постоянной квотой0.1CPU.
Gradle integration tests дополнительно проверяют отказ без записей при
`REFUSING_TRAFFIC`, отсутствие изменений БД от initialization и ротацию refresh
с хешем, полученным прежним Formatter-алгоритмом.

После трёх crypto-циклов initialization один раз запрашивает GC до загрузки
следующих JWT/MVC codecs. Это освобождает временные Argon2 work buffers и позволяет
SerialGC сжать расширенный heap до readiness; иначе пик heap+metadata+JIT может
превысить256MiB CI-стенда. GC является best-effort, поэтому успешность проверяется
реальными fresh startup/restart под прежним лимитом, а не unit-assertion о памяти.
В обработчиках HTTP принудительный GC не добавляется.
