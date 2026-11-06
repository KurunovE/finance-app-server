# Структура backend

Java и Kotlin используют один корневой package com.financetracker и одинаковое
разделение по слоям. Внутри слоя классы сгруппированы по auth/finance/sync.

```text
src/main/{java,kotlin}/com/financetracker/
├── controller/          HTTP-контроллеры и их интерфейсы API; advice для ошибок
├── service/             интерфейсы бизнес-сервисов и реализации в impl/
├── repository/          интерфейсы репозиториев и JDBC-реализации в impl/
├── model/
│   ├── dto/             отдельные request/response и межслойные DTO, суффикс Dto
│   └── entity/          модели строк SQL, суффикс Entity
├── mapper/              проекции финансовых Entity в прежний sync snapshot JSON
├── config/              Spring configuration, properties, startup guards
├── security/            фильтр, JWT, password policy, rate limits
├── exception/           прикладные исключения
├── support/json/        canonical JSON
└── admin/               offline CLI create-invite/recovery
```

URL API endpoints и HTTP-аннотации параметров объявлены в интерфейсах контроллеров.
Реализации вызывают сервисы и возвращают `ResponseEntity<T>`. `@Valid` находится
на параметрах интерфейсов, ограничения полей — в request DTO. Публичный путь удаления
аккаунта — `DELETE /v1/me`; профиль читается через `GET /api/v1/me`.
Контракты sync/recovery выделены в SyncRepository/RecoveryRepository. SQL оформлен
в text blocks преимущественно у JDBC-вызовов; повторяемые предикаты и локальные
SQL-скрипты могут храниться в именованных строковых переменных. Контроллеры
вызывают сервисы и принимают/возвращают DTO, сущности с password hash/внутренними
полями наружу не передаются. Финансовые CategoryEntity, ExpenseEntity,
MonthlyBudgetEntity, CategoryBudgetEntity используются при чтении репозиторием;
FinanceSnapshotMapper сохраняет wire-контракт и не выдаёт userId/normalizedName.
Auth UserEntity/AuthSessionEntity и recovery RecoveryStateEntity/SyncRecoveryEntity
также вынесены из вложенных классов. Entity — JDBC-модель, JPA/ORM не добавлялись.
Технический PreparedOperationDto также вынесен в отдельный файл; enum Action/EntityType
находятся в model/enums. Вручную объявленных вложенных типов больше нет.

```text
src/test/java/com/financetracker/
├── unit/
│   ├── controller/       HTTP-контракты без security-фильтров
│   ├── security/
│   ├── service/{finance,mail,sync}/
│   └── support/json/
├── integration/
│   ├── controller/{auth,sync}/
│   ├── repository/      migrations, роли и RLS
│   ├── service/finance/
│   ├── security/        реальные forwarded headers/Tomcat
│   └── ApplicationSmokeTest.java
└── support/             PostgreSQL/Mailpit/application fixtures
```

Из backend/: `./gradlew unitTest` без Docker, `./gradlew integrationTest` с Docker.
`./gradlew clean check bootJar` запускает общий test (оба набора), JaCoCo и прежние
80% gates. Доменный gate теперь объединяет классы домена из всех слоёв; пустая
выборка классов считается ошибкой вместо тихого пропуска. Общий gate включает весь
production-код. SQL/RLS/транзакции и wire payload не менялись при перемещении слоёв;
отдельное изменение поведения — статус успешной регистрации 201 вместо 202.

## Миграции

Единое имя `VNNN__description_in_snake_case.sql`:

- V001__initial_schema.sql
- V002__invites_jwt_families.sql
- V003__email_verification_and_reset.sql
- V004__recovery_epochs.sql

Изменены только ведущие нули версии в имени файла. SQL, description и логические
версии Flyway сохранены. Тест MigrationTest сначала применяет старые V1–V4 имена,
затем выполняет validate/migrate новым набором: history/checksums остаются прежними,
repair не нужен. Не переименовывать descriptions и не переписывать уже применённый SQL.
После обновления исходников выполнить clean, чтобы старые скопированные ресурсы
V1/V001 не оказались одновременно в build/resources или старом JAR.

Конструкторный Spring DI, Lombok builders и тестовые slices описаны в [di-and-testing.md](di-and-testing.md).

Все 20 DTO объявлены как Java record с Lombok @Builder; DTO-классов с @Value нет.

## Оформление объявлений

Обычное объявление переменной с инициализацией располагается на одной строке.
SQL text blocks и тела блоков лямбд/`switch` остаются многострочными.
