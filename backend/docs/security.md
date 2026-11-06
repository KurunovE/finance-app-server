# Авторизация и эксплуатация

Реализованы auth и финансовый sync по ТЗ 1.3. Email verification/reset и recovery epoch проверены локально;
публичные условия — в operations.md.

## Ключи и пароли

Argon2id: salt 16 байт, hash 32, parallelism 1, memory 19456 КиБ, iterations 2.
В БД формат `{argon2id}$argon2id$...`; успешный login обновляет более слабый хеш.
Пароли считаются в Unicode code points: 12–128; без изменения пароля и требований
цифр/символов. Встроенный конечный список — `security/common-passwords.txt`;
это поддерживаемый список частых паролей, а не онлайн-проверка базы утечек.

JWT HS256 использует случайные **raw binary** ключи минимум 32 байта из файлов.
Для локального запуска из корня проекта:

```sh
mkdir -p infra/secrets
umask 077
openssl rand -out infra/secrets/primary.key 32
```

Экспортируйте `JWT_KEY_FILE` как абсолютный путь к файлу. Файлы `.key` игнорируются
Git, права должны быть 0600, в production — read-only secret mount. Нет встроенного
секрета или автоматической генерации production-ключа. Без файла/со слабым ключом
приложение не стартует. Старые ключи в памяти не перечитываются автоматически.

Ротация: задайте через внешнюю конфигурацию `app.auth.keys.next=/path/next.key`,
сохраните `app.auth.keys.primary`, затем переключите `app.auth.current-kid=next`
и перезапустите. Выждите истечение последних JWT старого kid (15 минут от их
последней выдачи), затем удалите старый ключ из конфигурации и перезапустите.
При компрометации ключ удаляется сразу. Не используйте kid как URL или путь.

## Сессии и транзакции

Access JWT живёт 900 секунд, содержит sub/did/jti/ep/iat/exp и kid. Проверяются
алгоритм, подпись, срок и действительная семья в БД. Refresh: случайные 32 байта,
base64url без padding, 60 дней; БД хранит SHA-256 в lowercase hex.
Для устройства действует одна семья; новый login отзывает предыдущую семью.

Сначала блокируется строка пользователя, затем семьи. Refresh сохраняет хеш
использованного токена и меняет refresh/jti атомарно. При replay отзыв семьи
**коммитится до 401**, а не откатывается вместе с исключением. Использованные хеши
не очищаются для активной семьи. Клиент обязан сериализовать refresh: конкурентное
использование одного токена считается replay и отзывает также токен первого победителя.
Logout/replay немедленно исключают JWT этой семьи. Другие устройства не затрагиваются.

`status=DISABLED` отзывает все семьи через DB trigger; status также проверяется
при login, refresh и авторизации. Административное изменение статуса выполняется
оператором с соответствующими DB-полномочиями, публичного admin API нет.

DELETE /v1/me требует JWT и `{ "password": "..." }`. Удаляет только подтверждённый
аккаунт с cascade всех серверных financial/sync/token/device данных. Приглашение
сохраняет used_at, ссылка used_by очищается. Клиент до подтверждения предупреждает:
данные телефона остаются; старые токены не позволят отправить их в удалённый аккаунт.

## Приглашения

На новом Docker-томе выполняются 001-roles.sql и 002-invite-role.sql. Для существующей
БД этапа 1 сначала задайте DB_INVITE_PASSWORD в приватном infra/.env, обновите Compose
и **однократно**, если роль ещё отсутствует, выполните:

```sh
docker compose --env-file infra/.env -f infra/compose.yaml up -d --wait
docker compose --env-file infra/.env -f infra/compose.yaml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U finance_bootstrap -d finance \
  -f /docker-entrypoint-initdb.d/002-invite-role.sql
```

Затем запустите backend: Flyway V2 обновит схему, сохраняя V1 и финансовые данные.
Старые opaque-сессии V1 отзываются, пользователи входят заново.

Команда администратора запускается отдельным процессом **после миграций**:

```sh
java -jar backend/build/libs/finance-tracker-backend-0.1.0-SNAPSHOT.jar create-invite
java -jar backend/build/libs/finance-tracker-backend-0.1.0-SNAPSHOT.jar create-invite --ttl P2D
```

В окружении только этого процесса задайте INVITE_DB_URL, INVITE_DB_USER=finance_invite_admin,
INVITE_DB_PASSWORD (совпадает с выданным роли DB_INVITE_PASSWORD). Web-процессу эти
credentials не передаются. Команда не запускает Spring/HTTP/Flyway и не требует JWT-ключа.
Роль имеет SELECT/INSERT только на приглашения. Runtime создавать их не может.

Код — 16 случайных байтов (128 бит), base64url, по умолчанию P7D. Однократно выводится
после commit как результат команды, не пишется в обычные логи. Храните и передавайте
его приватно. В БД только SHA-256; погашение с блокировкой в транзакции регистрации.
Неудачная регистрация код не расходует.

## Лимиты и прокси

5 неудачных логинов на пару нормализованный email + IP за скользящие 15 минут;
следующий запрос получает 429/Retry-After. Неверное подтверждение удаления также
лимитируется отдельно. Общий API-лимит по IP — 60/мин (`app.auth.requests-per-minute`).
Операции с паролем на одной паре сериализованы, конкурентные попытки не обходят лимит.
Карты ограничены 10000 ключами (`app.auth.max-rate-limit-keys`): живые корзины
не вытесняются, при заполнении — 429. Лимитер рассчитан на один JVM-инстанс;
при нескольких инстансах требуется общее согласованное хранилище лимитов.

Local/test: server.forward-headers-strategy=none, используется socket IP.
Prod: native + **обязательный** TRUSTED_PROXY_REGEX только для адресов Caddy,
trusted-proxies пустой, remote-ip-header=x-forwarded-for, protocol-header=x-forwarded-proto.
Tomcat меняет remoteAddr только при совпадении непосредственного отправителя с regex.
Caddy обязан очищать/перезаписывать входящие X-Forwarded-*; внешний прямой доступ к
backend закрывается сетью. Реальный deployment Caddy/TLS проверяется на этапе 6;
на этом этапе проверен embedded Tomcat с доверенным и недоверенным отправителем.

CSRF отключён для JSON Bearer API без cookie/form/basic-аутентификации. HTTP-сессии
не создаются. Неизвестные маршруты закрыты denyAll, ошибки содержат безопасный code,
message и серверный requestId. Пароли, invite/refresh/JWT, Authorization и SQL-подробности
не попадают в API-ошибки. Request logging с телами не включать.

Подтверждение email/reset и SMTP: [email-security.md](email-security.md).
Новые JWT несут ep и отвергаются после смены recovery epoch; [протокол](recovery-protocol.md).
