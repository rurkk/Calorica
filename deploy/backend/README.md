# CI/CD и размещение backend

## Независимые проверки и выпуск

`Backend CI` запускает лёгкое определение изменённых путей на каждом PR и push main.
Обязательный статус для branch protection: `Backend CI / required` (проверьте точное
отображение в первом запуске GitHub). Он завершается и для docs без JVM/Android.

| Изменения | Проверки backend | Контейнерный выпуск main | Android |
| --- | --- | --- | --- |
| backend, его Gradle/wrapper/Dockerfile, deploy/backend | JVM + PostgreSQL + контейнер + CI lint | Да | Не запускается |
| app, корневой Android Gradle/wrapper | Нет | Нет | Существующий Android CI |
| обе части | Да | Да | Существующий Android CI |
| docs и Markdown, включая backend README | Нет | Нет | Не запускается |
| workflows или scripts/ci | actionlint, shellcheck, path-тесты, Compose validation | Нет | Android CI только при изменении его workflow |

Пути классифицируются по полному git diff без ограничения API на число файлов.
PR использует merge-base, push — previous SHA. Backend release вызывается только
после успешного обязательного статуса main; PR не получает production secrets.
Существующий ручной Android prerelease остаётся ручным и debug, теги ограничены
`android-vX.Y.Z`. Автоматический подписанный Android-релиз не входит в эту задачу.
Общего машинного API-контракта пока нет; при его введении требуется добавить
проверки совместимости клиентов в фильтры, не автоматический выпуск обеих частей.

Образ: `ghcr.io/<owner>/<repo>-backend:sha-<commit>`, linux/amd64.
Используется `GITHUB_TOKEN` с `packages:write`; GitHub Packages должен разрешать
публикацию из репозитория. Повторный запуск использует уже опубликованный digest
этого commit. Деплой получает исключительно `ghcr.io/...@sha256:...`, не mutable tag.
Устаревшая ревизия backend пропускается перед выпуском; документационный commit
не делает её устаревшей. GitHub concurrency хранит один pending выпуск, поэтому при
серии быстрых push проверьте выпуск последней версии и при необходимости повторите
её успешный workflow. Проверки и выпуск разделены в reusable workflows; не добавляйте прямой триггер
release в обход `Backend CI`. Изменения только CI не выкатывают приложение.

## Настройки перед первым реальным деплоем

В этой задаче ничего не публиковалось на GitHub/VPS. Для включения автоматического
деплоя нужны отдельное разрешение владельца и следующие настройки:

- Repository variable `BACKEND_DEPLOY_ENABLED=true` (по умолчанию deploy пропускается).
- Environment `backend-production`; variables `BACKEND_DEPLOY_HOST`,
  `BACKEND_DEPLOY_USER`, `BACKEND_DEPLOY_PORT` (22 по умолчанию),
  `BACKEND_DEPLOY_ROOT` (например `/opt/calorica`).
- Environment secrets `BACKEND_SSH_KEY`, `BACKEND_SSH_KNOWN_HOSTS`.
  Fingerprint хоста проверяется вне CI; `ssh-keyscan` во время деплоя не используется.
- На хосте Linux: Bash, Docker/Compose, `flock`, GNU coreutils; выделенный deploy user
  с доступом к Docker и только подготовленному каталогу Calorica. Docker-доступ
  практически эквивалентен root; используйте отдельный ключ и ограничения доступа.
- `shared/backend.env` по образцу `backend.env.example`, режим 600, каталог 700.
  Для закрытого GHCR-пакета на хосте заранее нужен read-only registry login.
  Production DB-пароль хранится на хосте, не пересылается workflow.
- Подтверждённые свободный loopback-порт, API-поддомен и TLS, ресурсы VPS (начальные
  лимиты backend 768 MiB/1 CPU, PostgreSQL 512 MiB/0.5 CPU требуют проверки нагрузкой).
  На ARM-хосте сначала согласуйте изменение платформы выпуска и протестируйте образ.

Compose-проект всегда `calorica-backend`; отдельные сеть, том, контейнеры и порт БД
без публикации. Backend слушает только выбранный loopback-порт хоста. Действующий
Nginx/Yarumo не меняется скриптом. Отдельную TLS-конфигурацию API необходимо добавить
по разрешению администратора; reverse proxy должен ограничивать тело запроса
(например 1 MiB) и таймауты. Приложение пока не доверяет X-Forwarded-*.
Доменные лимиты запросов и NUMERIC фиксируются вместе с будущим API.

## Деплой и откат

Workflow сохраняет каждый набор deploy-файлов в отдельном `releases/<sha>-<run>-<attempt>`.
`deploy.sh` валидирует digest, берёт host lock и использует только свой Compose-проект:

1. Проверка env/Compose, загрузка образов, запуск/проверка БД.
2. `pg_dump -Fc` в `shared/backups` до старта приложения и миграций.
   Ошибка/пустая копия прекращает выпуск.
3. Старт backend; Liquibase выполняется при запуске. Docker healthcheck проверяет
   readiness, включая БД, `up --wait` ограничен по времени.
4. При неготовности возвращается предыдущий образ с его сохранённой Compose-конфигурацией.
   Без предыдущей версии неготовый backend останавливается. БД не откатывается.
5. Только успешная версия становится ссылкой `current`. Образы/тома соседей не трогаются.

Миграции должны быть совместимы с предыдущим сервером. Откат приложения не спасает
от разрушительной миграции; такие изменения требуют expand/contract и отдельной
проверки. Версию/настройки PostgreSQL нельзя обновлять обычным backend-деплоем без
отдельного плана обновления БД и резервной копии до изменения контейнера БД.
Плановый ручной откат — новый запуск скрипта из сохранённого release-каталога
с предыдущим digest и тем же root; выполняйте только после проверки совместимости.
Не используйте `down -v`, `prune` или восстановление поверх production автоматически.

## Копии и восстановление

Скрипт обеспечивает локальную копию перед миграцией. Ежедневное расписание, 7 локальных
и 14 внешних зашифрованных копий, уведомления и контроль возраста копии ещё не
настроены: нужны внешнее хранилище, доступы и ответственный. До реальных данных это
блокер. Docker logs ограничены размером; удаление логов по возрасту до 14 дней также
нужно настроить на хосте. Месячная доступность/RPO/RTO не подтверждены каркасом.

Для учебного восстановления создайте отдельный Compose-проект и чистую БД, не
используйте имя production-проекта. Передайте выбранный dump в `pg_restore`:

```sh
# Только на выделенном чистом тестовом стенде с собственным env и томом:
BACKEND_IMAGE='<registry>@sha256:<digest>' docker compose -p calorica-restore \
  --env-file restore.env -f compose.yml up -d --wait db
BACKEND_IMAGE='<registry>@sha256:<digest>' docker compose -p calorica-restore \
  --env-file restore.env -f compose.yml exec -T db \
  sh -c 'pg_restore --exit-on-error --no-owner -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < backup.dump
```

Затем запустите совместимый backend на другом `API_PORT`, проверьте readiness,
Liquibase и контрольные данные. Замерьте возраст копии и время восстановления.
Это инструкция, не заявление о проверенном восстановлении внешней production-копии.

## Известные непроверенные пункты

Фактические GitHub Actions, GHCR push/pull, SSH/VPS, TLS, совместное размещение с Yarumo,
production-секреты, внешние backups/мониторинг и production rollback требуют стенда
и разрешения. Эта конфигурация не означает, что production-деплой выполнен.
