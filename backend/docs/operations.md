# Локальная эксплуатация и граница публичного релиза

Рабочий внутренний backend проверяется из готового JAR, с PostgreSQL 18 и TLS.
Публикация в интернет в этой работе не выполнялась. GitHub Actions настроен на каждый
push и pull request: тесты и JaCoCo gates 80%. См. [README](../../README.md#ci-в-github-actions).

## Сборка и контейнеры

Из корня FinanceApp:

```sh
cd backend
GRADLE_USER_HOME="$PWD/../.gradle-user-home" ./gradlew clean check bootJar --no-daemon
cd ..
docker build -f infra/Dockerfile -t finance-backend:local .
python3 scripts/verify-local.py
```

Последняя команда использует **свои временные контейнеры**, случайные пароли/ключи,
и сохраняет артефакты только в backend/build/local-verification-*. Она не подключается
к пользовательской БД и не меняет рабочие тома.
Создаёт краткоживущий тестовый сертификат, явно доверяет ему через CA trust store,
проверяет hostname/подпись TLS; проверки сертификатов не отключаются.

Проверяются register/login двух устройств и второго аккаунта, push/pull, повтор,
conflict, tombstone, logout, обычный HTTP на TLS-порту, подтверждение email, сброс
пароля и смена recovery epoch. Итог — report.json, не содержащий credentials.
Ключи и логи остаются в игнорируемом build; это только тестовые данные.

Dockerfile backend использует JRE 21 с зафиксированным base digest и UID/GID 10001.
В образ копируется только JAR; .env/ключи/backups/Gradle caches исключены из контекста.
JWT и TLS secrets передаются read-only secret mounts с правами чтения для UID 10001.
Для контейнерного запуска задать DB_URL на приватный hostname БД, SERVER_ADDRESS на
приватный интерфейс (в контейнере обычно 0.0.0.0); backend/БД не публиковать наружу.
Установить memory limits, read-only root FS и tmpfs /tmp для Tomcat. Настоящие secrets
в образ не включать. Обновление base digest — отдельная проверенная операция с тестами.

## TLS и Caddy

Prod требует TLS_KEY_STORE (PKCS12), TLS_KEY_STORE_PASSWORD, JWT_KEY_FILE и
TRUSTED_PROXY_REGEX. Сертификат backend должен проверяться Caddy, без skip verify.
`infra/reverse-proxy/Caddyfile` принимает API_DOMAIN, BACKEND_ADDRESS (host:TLS-port),
BACKEND_TLS_NAME; публичный CA backend монтируется /run/secrets/backend-ca.pem.
Простой HTTP получает 426, HTTPS проксируется на HTTPS backend. Заголовки X-Forwarded-For
и X-Forwarded-Proto перезаписываются; Forwarded удаляется. Размер body ограничен 256KB.
Прямой доступ к backend ограничить сетью; regex доверия содержит только адреса Caddy.

Шаблон синтаксически проверен Caddy 2.10.2; реальная доменная/ACME-конфигурация,
обновлённый и проверенный на уязвимости образ прокси, firewall и публичный сертификат
проверяются на целевом стенде перед публикацией. Локальный prod-профиль прошёл HTTPS
с проверкой сертификата, а spoofed forwarded headers проверены embedded Tomcat.

## Резервное копирование

Владелец самостоятельно создаёт и хранит резервные копии выбранными им средствами.
В проекте нет инструментов создания архивов, проверки их восстановления или снимков
исходников. При ручном восстановлении БД применяется протокол recovery ниже.

## Recovery после потери подтверждённых операций

В backup обязательно входят sync_changes, sync_operations и user_sync_state.
Согласованность счётчика не доказывает отсутствие потери операций, подтверждённых
**после** snapshot. Нельзя откатить production на
старый snapshot и просто открыть прежний push/pull клиентам.

Реализован recovery epoch/full resync с обязательной остановкой API при restore,
отзывом сессий, административной сверкой/активацией и replay устройства до фиксированного
cutoff. Полная процедура и обязательные действия клиента — [recovery-protocol.md](recovery-protocol.md).
После ручного восстановления выполнить `recovery-prepare` до открытия API, даже если
копия содержит recovery metadata. Архивы схемы до V4 сначала обновить в изолированном
стенде, затем выполнить prepare.

Rollback приложения до V3/V4 недопустим без отдельной миграции совместимости. Новые
клиенты должны сохранить данные и перейти на email/epoch-контракт. Схему/Flyway history
не откатывать. При ошибке остановить writers и выпустить исправление поверх схемы.
Реестр удалений и WAL/PITR не реализованы; full resync сам по себе не восстанавливает
подтверждённые после snapshot действия и не предотвращает возвращение удалённых аккаунтов.
До ручной сверки удалений/данных оставлять RECOVERY.

SMTP и ссылки клиента: [email-security.md](email-security.md). Prod дополнительно требует
SMTP credentials, MAIL_FROM и HTTPS MAIL_PUBLIC_URL; локальная доставка проверена Mailpit.

## Условия публичного запуска, которые ещё не закрыты

- Реальная доставляемость SMTP/домена и клиентский UX verify/reset/recovery; backend проверен локально.
- Зашифрованный диск БД/тома и swap, подтверждённые средствами целевого хоста.
- Реальный Caddy/TLS/firewall deployment, управление secret mounts и аудит образов/зависимостей.
- Внешняя память об удалениях и сверка удалений/потерь на целевом стенде; backend-протокол recovery epoch реализован. Резервные копии находятся в ведении владельца.
- Успешный прогон CI в GitHub ещё не подтверждён; для запуска загрузить проект вместе с `.github/` в репозиторий с включёнными Actions.

Эти условия не заменены флагами «шифрование включено» и не выдаются за выполненные.
Локальный внутренний MVP не равнозначен публичному production-релизу.
