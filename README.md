# Finance Tracker backend

Реализация по `finance_backend_spec_v1_1_java21.md`: **рабочий внутренний backend**.
Приглашения, подтверждение email/reset, JWT/refresh-сессии, категории/расходы/бюджеты, sync push/pull,
recovery epoch/full replay, изоляция RLS, удаление аккаунта, локальный HTTPS. Резервное копирование владелец выполняет самостоятельно.
CI настроен на каждый push и pull request в GitHub Actions. Публичный production-релиз ещё не готов:
условия перечислены в [operations.md](backend/docs/operations.md).

Требования: JDK 21, Docker с запущенным daemon, доступ к Maven Central,
Gradle Plugin Portal и официальным дистрибутивам Gradle.
Spring Boot 3.5.16 / Spring 6.2.19 и JUnit 5.13.4 согласованы по решению пользователя.

## Локальная база

Скопируйте `infra/.env.example` в `infra/.env`, задайте четыре разных приватных
пароля: `POSTGRES_BOOTSTRAP_PASSWORD`, `DB_RUNTIME_PASSWORD`, `DB_MIGRATOR_PASSWORD`,
`DB_INVITE_PASSWORD`.

```sh
docker compose --env-file infra/.env -f infra/compose.yaml up -d --wait
```

На новом томе bootstrap создаёт `finance_runtime`, `finance_migrator`,
`finance_invite_admin` и схему `app`.
Flyway при старте backend создаёт таблицы под migrator; JDBC pool использует runtime.
Bootstrap-учётные данные приложению не передаются. Порт БД опубликован только на loopback.

Если том создан на этапе 0, init-скрипт автоматически не повторится. Для такого тома,
где роли ещё отсутствуют, выполните однократно после `up`:

```sh
docker compose --env-file infra/.env -f infra/compose.yaml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U finance_bootstrap -d finance \
  -f /docker-entrypoint-initdb.d/001-roles.sql
```

Существующие данные и том не удаляйте. Скрипт не предназначен для повторного создания
имеющихся ролей или ротации паролей. Изменение `.env` само по себе не меняет пароль
существующей роли. Подробнее: [БД и транзакции](backend/docs/database.md).
Для тома этапа 1 отдельно добавляется роль приглашений — см.
[инструкцию обновления и авторизации](backend/docs/security.md).
Том и существующие роли не пересоздавайте.

Секреты backend экспортируйте из приватного окружения отдельно;
`.env` автоматически приложением не загружается. Для JWT обязателен `JWT_KEY_FILE`
с абсолютным путём к случайному binary-ключу >=32 байт; создание ключа и команды
`create-invite` описаны в [security.md](backend/docs/security.md).

## Сборка

Gradle Wrapper присутствует в `backend/`. Первый запуск загружает Gradle 8.14.4,
поэтому требуется доступ к `services.gradle.org` и серверам распространения Gradle.
Gradle успешно загружен; в Wrapper закреплена официальная SHA-256.
Docker доступен, интеграционные тесты с PostgreSQL 18.3 выполнены.

JUnit закреплён отдельно: 5.13.4. Используются Mockito, @WebMvcTest и ограниченные
@SpringBootTest-контексты; полные HTTP/recovery fixtures сохранены для сквозных проверок.
Проверенная команда (кэш внутри директории проекта):

```sh
cd backend
GRADLE_USER_HOME="$PWD/../.gradle-user-home" ./gradlew clean check bootJar --no-daemon
```

Результат: **BUILD SUCCESSFUL**. Проверки используют настоящий PostgreSQL 18.3,
раздельные роли и RLS. Пороги общего и доменного покрытия 80% сохранены.
Актуальные тесты, покрытия и границы готовности: [итоговый отчёт](backend/docs/implementation-report.md).
Контракт реализованных маршрутов: [OpenAPI](backend/docs/openapi.yaml).

Git в этой директории пока не инициализирован; remote не задан. Публичный deployment
не настроен. Java и Kotlin реализуют осмысленные части одного backend.
Публичные условия эксплуатации, реальная SMTP-доставка и клиентские экраны остаются отдельной работой.

## CI в GitHub Actions

Workflow [backend-ci.yml](.github/workflows/backend-ci.yml) запускается на каждый push
во все ветки и теги, а также на pull request, без фильтра по изменённым файлам.
Правила событий: [документация GitHub Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).
На Ubuntu с Java 21 и Docker выполняется `./gradlew clean check`: все unit- и
интеграционные тесты, общая и четыре доменных проверки покрытия 80%.
Testcontainers создаёт временные PostgreSQL/Mailpit; тестовые пароли и JWT-ключи
генерируются фикстурами, production-секреты и `infra/.env` не нужны.

XML/HTML-отчёты тестов и JaCoCo сохраняются на 7 дней в артефакте
`backend-test-reports`, в том числе при падении проверок. Результат доступен во вкладке
**Actions** после загрузки проекта вместе с `.github/` в GitHub с включёнными Actions.

## Запуск локального backend

После подготовки БД и экспорта DB_URL, DB_RUNTIME_USER/DB_RUNTIME_PASSWORD,
DB_MIGRATOR_USER/DB_MIGRATOR_PASSWORD и JWT_KEY_FILE из приватного окружения:

```sh
SPRING_PROFILES_ACTIVE=local java -jar backend/build/libs/finance-tracker-backend-0.1.0-SNAPSHOT.jar
```

Local слушает 127.0.0.1:8080. Для писем запустите Compose с `--profile mail`;
Mailpit inbox: http://127.0.0.1:8025. Регистрация теперь 201, затем verify email и login.
Подробности: [email](backend/docs/email-security.md), [recovery](backend/docs/recovery-protocol.md). Приглашение выпускается отдельным процессом create-invite
с INVITE_DB_* credentials по security.md. Для защищённого удалённого доступа используйте
подготовленный TLS/proxy профиль; публичная эксплуатация требует условий operations.md.

## Проверка готового JAR

```sh
docker build -f infra/Dockerfile -t finance-backend:local .
python3 scripts/verify-local.py
```

Скрипт сам создаёт временные PostgreSQL, сертификат и ключи внутри `backend/build/`,
проверяет HTTPS, два устройства/аккаунта, sync, подтверждение email, сброс пароля
и смену recovery epoch. Существующие БД/тома не используются. Java 21 (`java`, `keytool`), Python 3
и Docker должны быть доступны. Детали: [синхронизация](backend/docs/sync-protocol.md),
[безопасность](backend/docs/security.md), [эксплуатация](backend/docs/operations.md).

Структура слоёв, модели Entity/DTO и группы тестов: [project-structure.md](backend/docs/project-structure.md).

DI, Lombok и ограниченные контексты: [di-and-testing.md](backend/docs/di-and-testing.md).
