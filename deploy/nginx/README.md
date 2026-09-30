# HTTPS учебного стенда Calorica

API: `https://caloricaitmo.duckdns.org`.
Readiness: `https://caloricaitmo.duckdns.org/actuator/health/readiness`.
В DuckDNS IPv4 этого имени указывает на `62.84.122.55`; AAAA-запись не задана.
Если внешний IPv4 VPS изменится, обновите значение в панели DuckDNS.

Nginx использует существующие процессы на Yarumo и проксирует запросы в
`127.0.0.1:18090`. PostgreSQL остаётся в отдельной Docker-сети. Конфигурация
Yarumo не редактируется; изменения собственного vhost применяются через
`nginx -t` и graceful reload.

## Файлы на хосте

| Файл репозитория | Размещение на сервере |
| --- | --- |
| `caloricaitmo.duckdns.org.conf` | `/etc/nginx/sites-available/caloricaitmo.duckdns.org`, root:root, 644 |
| Ссылка на vhost | `/etc/nginx/sites-enabled/caloricaitmo.duckdns.org` |
| `calorica-nginx-reload` | `/usr/local/sbin/calorica-nginx-reload`, root:root, 755 |
| Webroot ACME | `/var/www/calorica-acme/.well-known/acme-challenge/` |
| Сертификат | `/etc/letsencrypt/live/caloricaitmo.duckdns.org/fullchain.pem` |
| Приватный ключ | `/etc/letsencrypt/live/caloricaitmo.duckdns.org/privkey.pem`, только на сервере |

Существующие `options-ssl-nginx.conf` и `ssl-dhparams.pem` Certbot используются
повторно. Приватные ключи, ACME-аккаунты и заполненный backend.env в Git не входят.

## Первичная настройка нового хоста

Сначала подготовьте backend по `../backend/README.md`, настройте DNS и проверьте
локальный readiness. Нужны Nginx, Certbot с webroot plugin и зарегистрированный
Let's Encrypt аккаунт. На действующем Yarumo они уже установлены.

Полный vhost содержит ссылки на сертификат, поэтому до его выпуска временно
установите только HTTP-блок:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name caloricaitmo.duckdns.org;
    access_log off;
    location ^~ /.well-known/acme-challenge/ {
        root /var/www/calorica-acme;
        default_type text/plain;
        try_files $uri =404;
    }
    location / { return 503; }
}
```

Создайте webroot с режимом 755 и ссылку в sites-enabled. Проверьте конфигурацию,
выполните reload и убедитесь, что временный файл в ACME-каталоге читается по
публичному HTTP URL. Установите hook из репозитория с владельцем root и режимом
755. Затем выпустите сертификат через существующий аккаунт:

```sh
sudo certbot certonly --non-interactive --webroot \
  --webroot-path /var/www/calorica-acme \
  --cert-name caloricaitmo.duckdns.org \
  --domain caloricaitmo.duckdns.org --key-type ecdsa \
  --preferred-challenges http \
  --deploy-hook /usr/local/sbin/calorica-nginx-reload
```

После выпуска замените временный HTTP-блок полным vhost из репозитория.
Перед заменой сохраните предыдущую версию собственного файла; при ошибке
проверки верните её. Применение:

```sh
sudo nginx -t && sudo systemctl reload nginx
curl --fail https://caloricaitmo.duckdns.org/actuator/health/readiness
```

Файлы Nginx применяются отдельно от container CI/CD. Backend Actions копирует
только Compose и deploy.sh; изменение этого каталога не выкатывает backend,
не пересобирает Android и не применяет Nginx автоматически. После изменения
vhost требуется повторить проверку/установку на хосте.

## Продление и проверки

Существующий `certbot.timer` обновляет сертификаты. Для этой линии Certbot
сохраняет webroot и deploy-hook; после успешного обновления hook проверяет
конфигурацию Nginx и выполняет reload. Проверка только линии Calorica:

```sh
sudo certbot renew --cert-name caloricaitmo.duckdns.org \
  --dry-run --run-deploy-hooks --no-random-sleep-on-renew --non-interactive
systemctl list-timers --all certbot.timer
```

HTTP перенаправляется на тот же URI по HTTPS (308). ACME-проверка остаётся
доступной по HTTP. Тело запроса ограничено 1 MiB, соединение с backend — 3 секунды,
чтение/запись upstream — 15 секунд, client body — 10 секунд, keepalive — 15 секунд.
Access log этого vhost отключён. Кэш ответов API выключен. Входные forwarded
заголовки заменяются Nginx; backend по-прежнему не доверяет им автоматически.

30 сентября 2026 проверены публичные TLS/health, redirect, 401 Problem Details
для закрытого `/api/products`, 413 для тела больше 1 MiB и успешное пробное
продление с hook. Сертификат действителен до 29 декабря 2026. Бизнес-API и
пользовательские сессии ещё не реализованы; открытие главного URL не является
веб-интерфейсом приложения.

Основания: [Nginx proxy](https://nginx.org/en/docs/http/ngx_http_proxy_module.html),
[Certbot webroot и hooks](https://eff-certbot.readthedocs.io/en/stable/using.html),
[Let's Encrypt HTTP-01](https://letsencrypt.org/docs/challenge-types/).
