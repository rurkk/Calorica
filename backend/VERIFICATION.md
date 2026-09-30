# Проверка каркаса — 28 сентября 2026

База ветки: `origin/main` (`fb281cab469e0eab3cf303db4662e7a7c821a621`).
Проверки выполнялись локально в отдельном worktree, JDK 21, PostgreSQL 17.11,
Docker/Colima на macOS ARM64. Чужие контейнеры и исходный checkout не изменялись.

## Выполнено

- `./gradlew check bootJar`: успешно, 3 интеграционных теста; в финальном прогоне
  новая пустая PostgreSQL. Проверены запуск HTTP, закрытый доступ GET/POST с
  поддельным bearer token, отсутствие cookie-сессии и внутренних деталей в ошибке.
- Liquibase: создание `calorica`, запись changeset в `public.databasechangelog`,
  `validate` и повторный `update` без повторного применения.
- Запуск собранного JAR с профилем prod: readiness 200; при остановке только
  тестовой БД readiness 503 и liveness 200; после возврата БД readiness 200.
- `pg_dump -Fc` и `pg_restore --exit-on-error` в отдельную пустую тестовую БД:
  схема и changeset `001-foundation | EXECUTED` восстановлены.
- Actionlint 1.7.7: все пять workflows приняты. Shellcheck: оба shell-скрипта приняты.
  Обе Compose-конфигурации проходят `config --quiet` с тестовыми значениями.
- Path-классификатор: 8 наборов изменений (backend, wrapper, deploy, Android,
  обе части, docs, workflow, CI-script) проверены.
- Скрипт деплоя: в изолированном Linux-контейнере с подставным Docker проверены
  успешное переключение current, откат к предыдущему образу/Compose при failed
  readiness и запрет запуска при ошибке резервной копии. Это проверка логики
  скрипта, не настоящий production rollback.
- Команда получения digest для повторного выпуска проверена на публичном образе.
- Итоговый Dockerfile собран; `docker compose up --build -d --wait` прошёл на
  чистом отдельном томе. Backend: UID `10001:10001`, read-only root, healthy.
  Проверены 401/Problem Details, отказ БД (readiness 503, liveness 200), восстановление
  БД и перезапуск backend: ready 200, единственный changeset сохранён в `public`.
- `git diff --check`: успешно.

## Ревью и исправления

- Compose-restart выявил смену PostgreSQL default schema при совпадении роли
  `calorica` с созданной схемой. JDBC и служебные таблицы Liquibase явно закреплены
  в `public`; миграция не переписывалась. CI использует роль `calorica` и повторный
  запуск контейнера, чтобы этот дефект не скрывался за другим именем тестовой роли.

- Начальный health-тест ошибочно запрещал стандартное поле `groups` корневого
  Actuator health; исправлено ожидание, запрет деталей БД сохранён.
- Исправлены замечания shellcheck и именование файлов копий, убран конфликт
  имени логгера с базовым servlet-фильтром.
- Markdown исключён из Android trigger paths; tags Android ограничены своим
  namespace, ввод tag передаётся через env.
- Ошибка доступа/сети registry не считается отсутствующим образом: выпуск
  прекращается, чтобы повторный запуск не перезаписал уже существующий tag.
- Workflow lint охватывает все YAML, обязательный статус завершается для docs,
  устаревшая серверная ревизия пропускается перед выпуском.
- Образы build/runtime закреплены по digest; build использует официальный
  Gradle 8.14.3/JDK 21 и кэш зависимостей.

## Не подтверждено локальной проверкой

GitHub Actions на hosted runner, публикация GHCR, реальный SSH-деплой, TLS,
production rollback, нагрузка VPS, совместное размещение с Yarumo, ежедневные
и внешние копии, уведомления, RPO/RTO. Linux/amd64 образ выпуска проверяется
workflow; локальная машина — ARM64. Старой серверной версии пока нет, поэтому
проверка upgrade/совместимости со старым приложением появится со следующей миграцией.
Бизнес-API, сессии и критерии приёмки полного MVP не реализованы этой задачей.

## Экономный стенд — 30 сентября 2026

Отдельный worktree от `6fdb78e`, JDK 21, локальный Docker/Colima ARM64.
Android и соседние контейнеры не изменялись.

- `./gradlew --no-daemon check bootJar`: успешно, интеграционные тесты с отдельной
  PostgreSQL 17.11 под лимитом 96 MiB / 0.15 CPU. Итоговый Dockerfile собран.
- Deployment Compose: backend 256 MiB / 0.35 CPU, PostgreSQL 96 MiB / 0.15 CPU;
  `docker inspect` подтверждает лимиты. Heap 96 MiB, Serial GC, prod-профиль с
  двумя соединениями БД и максимум восемью HTTP workers.
- После удаления только собственного тестового тома выполнен холодный запуск
  обоих контейнеров и первой миграции через `up --wait --wait-timeout 150`.
- 100 readiness-запросов с параллелизмом 4: все 200. Закрытый `/api/products`: 401.
  Перезапуск backend: readiness 200. Остановка тестовой БД: readiness 503,
  liveness 200; возврат БД: readiness 200.
- `pg_dump -Fc` и `pg_restore --exit-on-error` в отдельную чистую БД проходят
  при том же лимите контейнера PostgreSQL. В обеих БД одна запись Liquibase.
- После этих проверок Docker stats: backend 189.7 MiB, PostgreSQL 28.4 MiB.
  Ранее после холодного запуска: 214.2 и 19.07 MiB. Снимок cgroup после перезапуска:
  backend current/peak с cache 190.2/199.9 MiB; PostgreSQL 35.3/41.1 MiB.
  `memory.events`: `oom_kill 0`, автоматических рестартов нет.
- Actionlint 1.7.7 принимает workflows; path-тесты и Linux mock-проверка deploy.sh
  проходят. Smoke CI теперь использует ограниченный deployment Compose вместо
  контейнера без ресурсных лимитов.
- GitHub Actions первоначального выпуска main прошёл, GHCR образ доступен
  анонимно. SSH, ОС Ubuntu 24.04/x86_64, Docker и свободный loopback-порт 18090
  проверены на Yarumo; приложение Calorica на VPS ещё не запускалось.

Не подтверждены этой проверкой: amd64-выпуск нового профиля, потребление под
нагрузкой на VPS и будущими бизнес-API. HTTP readiness может вернуться раньше
следующей Docker health-проверки; итоговые метрики собраны после `up --wait`.

## Реальный VPS и HTTPS — 30 сентября 2026

- [Backend CI / deploy](https://github.com/rurkk/Calorica/actions/runs/36771012536)
  выпустил и разместил `955123d2e3a5cae3a4e41afa925551ac4497315e` на Ubuntu 24.04
  x86_64 через отдельного пользователя calorica-deploy. Оба контейнера healthy,
  backend привязан к 127.0.0.1:18090, БД не публикует порт.
- VPS hard limits: backend 256 MiB / 0.35 CPU, PostgreSQL 96 MiB / 0.15 CPU.
  Снимок Docker stats: 212.9 и 54.32 MiB; OOM=false, автоматических рестартов 0.
- DuckDNS A caloricaitmo.duckdns.org → 62.84.122.55 подтверждён двумя резолверами.
  Отдельный vhost Nginx проходит nginx -t; установлен через graceful reload.
- Let's Encrypt ECDSA сертификат выпущен через существующий Certbot/webroot,
  действует до 29 декабря 2026. Приватный ключ хранится только на сервере.
- Внешняя проверка с доверенными CA: TLSv1.2, правильный SAN; readiness/liveness
  200 UP, HTTP 308 на тот же HTTPS URI, /api/products 401 application/problem+json,
  тело больше 1 MiB → 413, X-Content-Type-Options: nosniff.
- certbot renew --cert-name caloricaitmo.duckdns.org --dry-run --run-deploy-hooks:
  успешно; hook проверяет Nginx и выполняет reload. Используется certbot.timer.
- Контрольные суммы существующих vhost Yarumo/default сохранены; локальный
  HTTPS /health Yarumo возвращает UP после установки отдельного сайта Calorica.

Менялись конфигурация reverse proxy, hook и документация. Повторная JVM/Android
сборка не требуется; бизнес-API и сессии не добавлялись. Внешние backup/monitoring,
совместная нагрузка и полноценный rollback production ещё не подтверждены.
