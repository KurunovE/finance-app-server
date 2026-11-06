# Локальные проверки

Актуальное состояние — 2026-11-05. Проверены **115 unit-сценариев** и
**11 сценариев AuthControllerSliceTest** и **ServiceWiringTest (1/1)**.
Ошибок и пропусков нет.
Это частичная проверка: полный набор интеграционных тестов, JaCoCo gates и новый JAR
в этом проходе не запускались. Исторический результат 199 сценариев и coverage 95.05%
от 2026-10-31 не является результатом проверки текущего кода.

Стек проекта: Java 21, Spring Boot 3.5.16 / Framework 6.2.19, JUnit 5.13.4,
Mockito, Lombok. В Gradle задан Kotlin 2.4.21 и Gradle 8.14.4. Для доступной локальной
проверки Kotlin использован установленный компилятор 2.4.0.

## Что проверяется

- Unit-тесты проверяют finance rules/service, криптографию, лимитеры, recovery state,
  canonical JSON и почтовые сценарии.
- `ControllerContractTest` использует standalone MockMvc: связывание параметров
  интерфейсов, проверка DTO и тела/статусы `ResponseEntity`. Security-цепочки здесь нет.
- `AuthControllerSliceTest` использует `@WebMvcTest` с настоящей MVC/security цепочкой
  и mock-сервисами. Включены успешное удаление, неверный пароль, отсутствие авторизации,
  некорректный DTO, закрытие необъявленного пути и отсутствие стандартного пользователя.
  Настройки исключения автоконфигурации загружаются из `application.yml`.
- `ServiceWiringTest` использует ограниченный finance-контекст с mock-репозиторием.
- JDBC/RLS/migration/HTTP/SMTP/restore и proxy-тестам нужны Docker, PostgreSQL и Mailpit.
  H2 вместо PostgreSQL не используется.

## Штатные команды

Из каталога backend:

```sh
./gradlew unitTest
./gradlew integrationTest
./gradlew clean check bootJar
```

`check` включает общий test, JaCoCo и общую/четыре доменных проверки покрытия 80%.
Пустая выборка классов домена запрещена. Пороги и состав тестов не ослаблялись.

## Проверка в текущей среде

Gradle блокируется запретом служебного сокета. Docker CLI установлен, но сокет daemon
отсутствует; запуск интеграционных контейнеров сейчас невозможен.
Использован локальный JUnit Platform с согласованными зависимостями Boot 3.5.16,
Spring 6.2.19, JUnit 5.13.4 из кеша и агентом Byte Buddy.

Все Java main/test исходники после `delombok` проходят
`javac --release 21 -parameters -proc:none -Xlint:all -Werror`.
Kotlin проходит `-Wextra -Werror` с target 21.

Логи исправления замечаний и актуальные результаты описаны в
[code-review.md](code-review.md). Полный Gradle-прогон и новое измерение покрытия
нужно выполнить после появления Docker/Gradle-окружения.
