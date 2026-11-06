# Локальные проверки

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
Пустая выборка классов домена считается ошибкой.
