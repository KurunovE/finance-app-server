# Этап 2 — приглашения и авторизация

Дата: 2026-10-31. ТЗ 1.2; JDK 21, Spring Boot 4.0.9, JUnit 5.13.4.
Изменения ограничены FinanceApp, CI и deployment не запускались.

Реализованы V2 поверх неизменённой V1, отдельная роль finance_invite_admin,
приглашения 128 бит/P7D, команда create-invite, пользователи ACTIVE/DISABLED,
устройства, семьи и cascade. Старые opaque-сессии отозваны миграцией без потери
финансовых данных. На update с V1 это проверено отдельным integration-тестом.

Пароли: 12–128 Unicode code points, встроенный список, Argon2id 16/32/1/19456/2,
DelegatingPasswordEncoder, upgrade при успешном login. JWT HS256 с sub/did/jti/iat/exp,
TTL 900 секунд, kid и ключи из файлов >=32 байт. Nimbus выполняет подпись и проверку.
Refresh 256 бит/base64url, SHA-256 в БД, TTL 60 дней, ротация внутри family_id.
Replay коммитит отзыв семьи перед HTTP 401. Logout и DISABLED немедленно запрещают JWT.
DELETE /v1/me подтверждает пароль и каскадно удаляет только собственный аккаунт;
использованное приглашение остаётся погашенным.

HTTP: register/login/refresh/logout, GET /api/v1/me, DELETE /v1/me; остальные маршруты
закрыты denyAll. Отсутствуют form/basic/cookie-аутентификация и сгенерированный пароль.
Ошибки без private payload, нормализация email — strip + lower(Locale.ROOT).
Лимиты 5 ошибок на email+IP/15 минут и 60 API requests/IP/min, атомарные и ограниченные
по памяти. Embedded Tomcat проверен с доверенными и недоверенными forwarded headers.

Проверка `clean check bootJar --no-daemon`: BUILD SUCCESSFUL, 76 тестов,
0 failures/errors/skips на настоящем PostgreSQL 18.3. Пороги 80% не ослаблялись.

overall: 363/384 = 94.53%
com/financetracker/auth: 227/238 = 95.38%
com/financetracker/admin: 23/23 = 100.00%
com/financetracker/security: 99/100 = 99.00%
com/financetracker: 1/10 = 10.00%
com/financetracker/persistence: 13/13 = 100.00%

Матрица AUTH-01...20 проверяется AuthApiTest, AuthCryptoTest, RateLimitsTest,
ProxyHeadersTest и MigrationTest. Сохранены 41 проверки DB/RLS этапа 1 и HTTP smoke.
Проверены конкурентное погашение invite/одинаковый email, два refresh,
DISABLED одновременно с refresh, rollback invite при ошибке, stale JWT/jti/did,
HS384/none/неверная подпись/claims/kid, ротация ключей, границы пароля и JWT,
каскад financial/sync и неизменность другого аккаунта.

OpenAPI описывает фактически реализованные маршруты и локально проверен на разбор
и разрешение всех $ref. Исходники Java отформатированы через IDE; SQL/psql не
передаются общему форматтеру. После форматирования и BC 1.86 сборка повторена.

Инструкции: security.md, README и infra/.env.example. Реальный Caddy/TLS deployment,
email verification/reset и эксплуатационное шифрование/backup ещё не пройдены;
публичный релиз не заявляется. Финансовые сервисы и sync API — следующие этапы.
