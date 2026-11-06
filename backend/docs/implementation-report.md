# Состояние реализации Finance Tracker

Обновлено 2026-11-05. Этот файл описывает текущий backend; исторический результат
199 сценариев и 95.05% coverage от 2026-10-31 не относится к последним изменениям.

## Реализовано

- Java 21, Spring Boot 3.5.16 / Framework 6.2.19, Kotlin-сервисы finance,
  JDBC/PostgreSQL, Flyway V001–V004, JUnit 5.13.4, Mockito и Lombok.
- Интерфейсный конструкторный DI; отдельные DTO, модели Entity и mapper;
  транзакционные шаблоны и миграционные зависимости с qualifiers.
- Четыре API-интерфейса содержат HTTP mappings и аннотации параметров. Ограничения
  полей находятся в DTO, реализации контроллеров возвращают `ResponseEntity<T>`.
- Приглашения, Argon2-пароли, JWT HS256/kid/epoch, refresh families/replay,
  logout, подтверждение email и сброс пароля.
- Finance rules, оптимистические версии, идемпотентный push/pull, журнал изменений,
  recovery epoch/full replay/ack с транзакционным fence.
- Роли runtime/migrator, FORCE RLS и startup guards; безопасные ошибки и SLF4J-логи.
- Публичное удаление аккаунта восстановлено по `DELETE /v1/me`: маршрут совпадает
  с security matcher, OpenAPI и полными HTTP-тестами. `GET /api/v1/me` сохраняется.
- Исправлено имя исключаемой автоконфигурации UserDetailsService для Boot 3.5.
  MVC-тест загружает настройки приложения и проверяет отсутствие стандартного пользователя.
- Обычные объявления переменных с инициализацией оформлены на одной строке;
  SQL text blocks и реальные блоки кода сохраняют многострочное оформление.

## Проверено для текущего снимка

- Java main/test после обработки Lombok: `-Xlint:all -Werror`, без диагностик.
- Kotlin: `-Wextra -Werror`, без диагностик, локальным компилятором 2.4.0.
- Unit: 115/115; `AuthControllerSliceTest`: 11/11; `ServiceWiringTest`: 1/1.
- Регрессионные тесты сначала воспроизвели замечания ревью, затем прошли после исправлений.
- Ограничения покрытия 80% в Gradle сохранены; текущее покрытие заново не измерялось.

Gradle не запускается из-за ограничения служебного сокета; Docker daemon недоступен.
Поэтому полный `clean check bootJar`, интеграционные PostgreSQL/SMTP/restore проверки,
новый JAR, образ и deployment этим результатом не подтверждены. CI и публикация не выполнялись.
Детали: [code-review.md](code-review.md), [testing.md](testing.md).

## Что остаётся

Нужно повторить штатную полную сборку с настроенным Kotlin 2.4.21, Docker и JaCoCo.
Клиентский verify/reset/recovery UX находится вне backend. Сохранность локального файла
клиента сервер физически подтвердить не может; recovery не обещает RPO=0.

Перед публичным запуском остаются эксплуатационные задачи из
[operations.md](operations.md): SMTP-доставляемость, целевые TLS/proxy/firewall/secrets,
независимый реестр удалений и решение по WAL/PITR. Резервное копирование выполняет
владелец своими средствами. В этом проходе эти внешние системы не настраивались.

Дополнительные материалы: [структура](project-structure.md),
[DI и тестовые контексты](di-and-testing.md), [email](email-security.md),
[recovery](recovery-protocol.md), [OpenAPI](openapi.yaml).
