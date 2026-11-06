# Ревью backend Finance Tracker: замечания исправлены

Обновлено 2026-11-05. Подтверждённые дефекты P1 и P2 устранены и закрыты
регрессионными тестами. Проверялись backend Java/Kotlin, HTTP/security-контракты,
конфигурация, сервисы, JDBC и DTO. Инфраструктура вне backend и клиентское приложение
в этот аудит не входят.

## Исправленные дефекты

### P1: маршрут удаления аккаунта — исправлено

Ранее controller contract регистрировал `DELETE /api/v1/me`, тогда как Spring Security,
OpenAPI и полные HTTP-тесты использовали `DELETE /v1/me`. Это приводило к 403 для первого
пути и 404 для второго; сервис удаления не вызывался.

В [AuthControllerApi](../src/main/java/com/financetracker/controller/auth/AuthControllerApi.java)
теперь заданы полные пути методов. Удаление использует **`DELETE /v1/me`**, совпадая с
[security matcher](../src/main/java/com/financetracker/config/HttpSecurityConfiguration.java)
и [OpenAPI](openapi.yaml). Остальные пути сохранены, включая `GET /api/v1/me`.
Аннотации HTTP/`@Valid` остаются в интерфейсе, реализация контроллера содержит логику.
Standalone-тест обновлён на публичный путь.

В [AuthControllerSliceTest](../src/test/java/com/financetracker/integration/controller/auth/AuthControllerSliceTest.java)
добавлены проверки с настоящей security-цепочкой:

| Сценарий | Ожидаемый и полученный результат |
|---|---|
| Удаление с корректной аутентификацией и паролем | 204, вызван `AuthService.delete` |
| Сервис отклоняет неверный пароль | 403, `DELETE_REJECTED` |
| Нет авторизации | 401, сервис не вызывается |
| Некорректный DTO удаления | 400, сервис удаления не вызывается |
| Необъявленный `DELETE /api/v1/me` | 403, сервис удаления не вызывается |

### P2: исключение автоконфигурации Boot — исправлено

В [application.yml](../src/main/resources/application.yml) указано полное имя класса
из используемого Spring Boot 3.5.16:

```text
org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
```

Отдельное `excludeAutoConfiguration` из MVC-теста удалено. Тест загружает настройки
приложения и проверяет отсутствие `UserDetailsService`, поэтому неверная настройка
больше не скрывается тестовым исключением.

**Регрессионное подтверждение:** до исправления два сценария удаления возвращали
404 вместо 204/403, а проверка отсутствия стандартного пользователя падала. После
исправления весь MVC slice проходит. Исторический дефект не трактуется как обход JWT:
HTTP Basic и form login остаются отключёнными.

## Исправленные неточности и оформление

- `project-structure.md` описывает реальные интерфейсные HTTP-контракты,
  `ResponseEntity<T>`, DTO validation и публичный путь удаления.
- `implementation-report.md` и `testing.md` обновлены. Старые результаты
  199 сценариев / 95.05% coverage больше не выдаются за проверку текущего снимка.
- `di-and-testing.md` описывает новые security-enabled сценарии и проверку настроек
  приложения без отдельного тестового исключения автоконфигурации.
- Обычные объявления переменных с инициализацией оформлены на одной строке;
  SQL text blocks и реальные блоки кода остаются многострочными.

## Что уже реализовано в проекте

- Четыре API-интерфейса с HTTP-аннотациями и `@Valid`, ограничения полей в DTO,
  реализации контроллеров с `ResponseEntity<T>` / `ResponseEntity<Void>`.
- Конструкторный интерфейсный DI и отдельные service/repository/mapper слои.
- Приглашения, Argon2-пароли, JWT с `kid` и epoch, проверка живых сессий,
  refresh families/replay, logout, email verification и reset.
- Нормализация sync payload, идемпотентность, оптимистические версии, журнал изменений
  и отдельная транзакция для каждой операции batch.
- Recovery epoch, транзакционный fence, replay до cutoff и acknowledgement.
- JDBC с параметрами, SQL text blocks, enum-ограниченный выбор динамической таблицы,
  четыре миграции Flyway, роли/FORCE RLS и startup guards.
- Ограничения размера/глубины payload и числа ключей лимитера.
- Безопасные JSON-ошибки и Lombok `@Slf4j` / `log`; секреты не добавляются в сообщения логов.
- Фигурные скобки у `if`, `@Override`, serialVersionUID, nullability-контракт фильтра,
  безопасная проверка URL без схемы и однострочные обычные объявления переменных.

## Проверено после исправлений

| Проверка | Результат |
|---|---|
| Java main/test, Java 21, после `delombok` | `-Xlint:all -Werror`, без диагностик |
| Kotlin, локальный компилятор 2.4.0, target 21 | `-Wextra -Werror`, без диагностик |
| Unit-тесты | 115/115, без ошибок и пропусков |
| AuthControllerSliceTest | 11/11, без ошибок и пропусков |
| ServiceWiringTest | 1/1, без ошибок и пропусков |

Java проверялась через `delombok`, затем `javac -proc:none -Xlint:all -Werror`,
чтобы отделить обработку Lombok от диагностики runtime-аннотаций Spring/JUnit.
Подавления предупреждений, отключения тестов и снижения coverage thresholds не добавлены.

Тесты запускались через JUnit Platform с зависимостями Boot 3.5.16, Spring 6.2.19,
JUnit 5.13.4 из локального кеша и агентом Byte Buddy.
[Логи](../build/review-fixes-2026-11-05/) включают регрессионный отказ до исправления
и успешные проверки после него; автоматически сгенерированный тестовый пароль в
логе до исправления скрыт. Очистка `build` удалит эти локальные артефакты.

## Оставшиеся проверки и эксплуатационные задачи

Незакрытых подтверждённых дефектов из этого ревью нет. Следующие пункты являются
ограничениями проверки и эксплуатационными задачами, а не выполненными работами:

1. Повторить `./gradlew clean check bootJar` с настроенным Kotlin 2.4.21,
   полным набором интеграционных PostgreSQL/SMTP/restore тестов и JaCoCo gates 80%.
   Gradle здесь блокируется запретом служебного сокета. Docker CLI установлен,
   но сокет Docker daemon отсутствует. Текущее покрытие заново не измерялось.
2. Проверить полный профиль IntelliJ inspections, когда IDE получит доступ к workspace.
3. Перед публичным запуском закрыть задачи из [operations.md](operations.md):
   целевые TLS/proxy/firewall/secrets, SMTP-доставляемость, расписание/offsite backups,
   retention и restore drills, независимый реестр удалений и решение по WAL/PITR.
   Клиентский verify/reset/recovery UX находится вне backend.

CI, публикация, аудит актуальных уязвимостей зависимостей, нагрузочное тестирование,
пересборка контейнеров и production deployment этим проходом не выполнялись.
