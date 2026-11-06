# Этап 0 — подготовка проекта и окружения

Дата: 2026-10-31. **Definition of Done: FAIL — этап не принят.**

## Изменённые файлы

- `backend/settings.gradle.kts`, `backend/build.gradle.kts`: Boot 4.0.9, Kotlin 2.4.21,
  toolchain 21, Spring JDBC, Flyway, JUnit 5.13.4, Testcontainers, JaCoCo.
- `backend/src/main/java/com/financetracker/FinanceTrackerApplication.java`:
  точка входа Spring Boot.
- `backend/src/main/java/com/financetracker/security/HttpSecurityConfiguration.java`:
  разрешён только GET health, прочие маршруты denyAll, stateless.
- `backend/src/main/resources/application*.yml`: базовый, local/test/prod профили,
  секреты из env, безопасный health, обязательный TLS для prod.
- `backend/src/test/java/com/financetracker/ApplicationSmokeTest.java`:
  PostgreSQL Testcontainers и проверки через реальный HTTP.
- `infra/compose.yaml`, `infra/.env.example`: локальная PostgreSQL 18.3,
  порт только localhost, значения секретов не заданы.
- `.github/workflows/backend-ci.yml`: Java 21, Docker, check/bootJar, artifacts,
  отсутствие wrapper намеренно проваливает job.
- `.gitignore`, `README.md`, `backend/docs/`: инструкции и фактический статус.

## Реально выполненные команды и результаты

| Команда | Результат |
|---|---|
| `java -version` | Microsoft OpenJDK 21.0.12.1, arm64 |
| `/usr/libexec/java_home -V` | Найден один JDK 21 |
| `command -v gradle` | Gradle не найден |
| `docker info --format '{{.ServerVersion}}'` | Нет socket Docker daemon |
| `curl -I --max-time 15 https://services.gradle.org/distributions/gradle-8.14.3-bin.zip` | Exit 6: Could not resolve host |
| `git status --short` | Каталог не является Git-репозиторием |
| `git init -b main` | Operation not permitted для `.git` |
| `cd backend && ./gradlew clean check bootJar --no-daemon` | Exit 127: wrapper отсутствует |
| `docker compose --env-file infra/.env.example -f infra/compose.yaml config --quiet` | Exit 1: пустой обязательный пароль отклонён; секрет намеренно отсутствует в примере |

Unit tests: **не запускались**. PostgreSQL/API tests: **не запускались**.
JaCoCo overall и auth/security/sync/finance: **не измерено**.
CI: файл подготовлен, GitHub run не выполнялся; remote отсутствует.

## Качество и архитектура

Не введены дополнительные паттерны, абстракции, фиктивные Kotlin-классы и CRUD API.
Используются стандартные конфигурация Spring и JDBC, единственный test task.
Порог 0.80 задан для всего backend и отдельно для реализованных критических доменов,
без исключения production-классов. Реальная работоспособность gate пока не доказана.
Автоформатирование не выполнялось: steroid IDE tool и внешний форматтер отсутствуют.
Ручной просмотр сделан, но компиляцию и тесты он не заменяет.

## Оставшиеся действия перед завершением этапа

1. В окружении с разрешённой сетью получить официальный Gradle, проверить SHA-256,
   сгенерировать и проверить полный Wrapper. Самописный launcher не добавлять.
2. Запустить Docker, разрешить зависимости, проверить совместимость JUnit 5 с выбранным
   ручным жизненным циклом Spring и Testcontainers; устранить ошибки по результатам.
3. Получить bootJar, запустить приложение с локальной БД и проверить health.
4. Выполнить полный check, измерить coverage и доказать падение завышенного gate.
5. Настроить форматирование, выполнить проверку исходников.
6. Инициализировать Git в разрешённом окружении, проверить изменения и зафиксировать этап;
   запустить CI в реальном репозитории.

Этапы 1–7 не начаты: раздел 13 ТЗ запрещает переход до DoD предыдущего этапа.
Отсутствующий `finance_app_architecture_jdk21.md` отмечен; backend-ТЗ имеет приоритет.
Проект пока не является запускаемым и принятым MVP или завершённым этапом 0.

## Повторная попытка: Spring Boot 4.0.9

По запросу пользователя плагин и оба BOM заменены с 4.1.2 на 4.0.9.
Официальные требования Boot 4.0.9 подтверждают совместимость с JDK 21 и Gradle 8.14+.
JUnit 5.13.4 оставлен отдельно закреплённым; SpringExtension по-прежнему не используется.

Повторно выполнены `java -version` (JDK 21), `command -v gradle` (не найден),
`docker info --format '{{.ServerVersion}}'` (permission denied к Docker socket),
`curl -I --max-time 15 https://services.gradle.org/distributions/gradle-8.14.3-bin.zip`
(exit 6, DNS недоступен). Теперь ошибка Docker — запрет доступа к socket,
а не подтверждённое отсутствие daemon.

Смена версии не устраняет ограничения окружения. Этап остаётся FAIL до успешных
сборки, тестов, измерения покрытия и запуска. Исходное ТЗ сохранено без изменений.

## Попытка загрузки Gradle и тестового прогона по запросу пользователя

Фактически выполнены:

- Загрузка `https://services.gradle.org/distributions/gradle-8.14.3-bin.zip`
  через `curl --fail --location --connect-timeout 10 --max-time 30 --output
  /private/tmp/finance-gradle-8.14.3-bin.zip`: exit 6, `Could not resolve host`.
- Загрузка официальной SHA-256 с
  `https://downloads.gradle.org/distributions/gradle-8.14.3-bin.zip.sha256`:
  exit 6, `Could not resolve host: downloads.gradle.org`.
- Поиск готового Gradle/Wrapper в `.gradle`, `/Applications`, `/opt/homebrew`,
  `/private/tmp`: подходящие файлы не найдены.
- `docker info --format '{{.ServerVersion}}'`: доступ к Docker socket запрещён.
- Из `backend/`: `./gradlew clean test --no-daemon`: exit 127,
  `zsh:1: no such file or directory: ./gradlew`.

Gradle не загружен, SHA-256 не проверена, Wrapper не сгенерирован.
Тестовый runner не стартовал; нет результатов тестов и измерения покрытия.
Нужна среда с сетевым доступом к Gradle/Maven и доступом к Docker socket.


## Проверка после добавления Wrapper, 2026-10-31

По разрешению пользователя исследование продолжено без Amplicode, через файлы
и консольные команды. Обнаружены `gradlew`, `gradlew.bat`, wrapper properties
и wrapper JAR; прежнее утверждение об отсутствии Wrapper устарело.

Реально выполнены:

- `java -version`: Microsoft OpenJDK 21.0.12.1.
- `docker info`: exit 1, permission denied при обращении к Docker socket.
- Из `backend/`: `GRADLE_USER_HOME=/private/tmp/finance-gradle-home ./gradlew clean check bootJar --no-daemon`:
  exit 1, `java.net.UnknownHostException: services.gradle.org` при загрузке Gradle 8.14.3.

Компиляция и тесты не начались. Unit, PostgreSQL integration, API/security tests:
не запускались. JaCoCo overall и пакеты auth/security/sync/finance: не измерено.
README обновлён в соответствии с наличием Wrapper и фактическим препятствием.

DoD этапа 0: FAIL. Этапы 1–7 остаются не начатыми согласно разделу 13 ТЗ,
который требует пройти DoD предыдущего этапа. Для продолжения нужны доступ
к распространению Gradle, зависимостям Maven и Docker socket. Ограничения
покрытия и обязательность PostgreSQL-тестов не изменялись.


## Восстановление окружения и локальные проверки, 2026-10-31

Этот раздел обновляет прежние сведения о блокировках. По разрешению пользователя
использован Docker; изменения файлов выполнялись внутри FinanceApp.

- Docker Server 29.8.2 доступен; Microsoft OpenJDK 21.0.12.1.
- Wrapper загрузил официальный Gradle 8.14.3. В properties закреплена официальная
  SHA-256 `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`.
- Кэш Gradle находится в `.gradle-user-home/` внутри проекта и добавлен в gitignore.
- `clean check bootJar --no-daemon`: BUILD SUCCESSFUL, 3 теста,
  0 ошибок, 0 пропусков; настоящий PostgreSQL 18.3 через Testcontainers 2.0.5.
- Исправлено поведение health в Boot 4: probes явно отключены, ответ содержит
  только статус. Проверка точного ответа сначала падала, после исправления проходит.
- Исправлен тестовый порт: аргумент `--server.port=0` имеет приоритет над YAML;
  прежний default property ошибочно оставлял порт 8080.
- Исправлены данные доменных JaCoCo tasks: передаётся объект Test, а не provider,
  который интерпретировался как коллекция выходных файлов, включая каталог binary.
- JaCoCo LINE: overall 9/11 (81,82%), security 8/8 (100%). XML и HTML сформированы.
  auth/finance/sync отсутствуют и пропущены как ещё не реализованные домены.
  Kotlin production-классов пока нет, поэтому их покрытие пока не измеряется.
- Через временный init script в игнорируемом build установлен порог 100%:
  `jacocoTestCoverageVerification` ожидаемо завершился exit 1 из-за покрытия 0.81.
  Рабочие ограничения 80% не изменялись. Последующий штатный `check bootJar` успешен.
- Готовый `finance-tracker-backend-0.1.0-SNAPSHOT.jar` отдельно запущен
  с профилем local и временным PostgreSQL 18.3: HTTP 200 `{"status":"UP"}`.
  Приложение остановлено, проверочный контейнер удалён. Credentials случайные,
  передавались через окружение, в исходники не записывались.
- Изменённые Java, YAML, Gradle Kotlin DSL и wrapper properties отформатированы
  через доступный инструмент IntelliJ `reformat_file`.

Пользователь указал не использовать предложенный прогон CI. CI не запускался;
его успешность не заявляется. Git в директории не инициализирован, remote не задан.
Локальная часть этапа 0 проверена; исходный DoD с обязательным CI целиком
не подтверждён. Этап 1 в этой сессии не начат.


Пользователь затем явно разрешил переход к этапу 1 без CI.
Актуальный результат: [отчёт этапа 1](stage-1-report.md).
