# Актуальное решение по зависимостям

Пользователь разрешил понизить Spring для стандартной работы Boot test annotations
с JUnit 5. Выбран Spring Boot 3.5.16 / Spring Framework 6.2.19, JDK 21, JUnit 5.13.4.
Boot plugin и dependency BOM одной версии; Jackson 2.21.4, Flyway 11.7.2,
Testcontainers 1.21.4, Mockito 5.17.0 и Lombok 1.18.46 управляются этим BOM.
Annotation processor отдельно получает тот же BOM. Kotlin 2.4.21; lombok compiler plugin удалён, поскольку Kotlin использует record-конструкторы.

Источники: [требования Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[Spring TestContext](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/support-classes.html),
[Lombok Builder](https://projectlombok.org/features/Builder).
Доступность 3.5.16 и версии зависимостей сверены по Maven Central metadata/POM.

Прежнее решение Boot 4/Jackson 3 и отказ от SpringExtension отменено новым указанием
пользователя. Детали реализации — [di-and-testing.md](di-and-testing.md).

## Историческое решение до понижения Spring

# Зависимости и совместимость

Проверено по официальной документации 2026-10-31:

- [Spring Boot 4.0.9](https://docs.spring.io/spring-boot/4.0/system-requirements.html):
  Java 21 поддерживается; для Kotlin 2.4.21 выбран Gradle Wrapper 8.14.4.
  По запросу пользователя заменена исходная версия 4.1.2 из ТЗ: проверяем ветку 4.0.
  Сборка и запуск подтверждены 2026-10-31 на JDK 21 с PostgreSQL 18.3.
- [Kotlin 2.4.21](https://kotlinlang.org/docs/releases.html): закреплены JVM и Spring plugins;
  toolchain обоих языков — 21. Kotlin-домен появится на этапе 3.
- [Boot BOM](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html):
  версии JDBC, PostgreSQL driver, Flyway, Testcontainers задаются через BOM 4.0.9;
  используются MVC и модуль Flyway с PostgreSQL support. JPA и WebFlux не подключены.
- [JUnit 5.13.4](https://docs.junit.org/5.13.4/user-guide/): пользователь явно выбрал
  JUnit 5 с отдельной фиксацией вместо JUnit 6 из BOM Boot. Используется enforced BOM,
  чтобы не смешивать Jupiter 5 и Platform 6.
- [SpringExtension Spring 7](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/support-classes.html)
  требует Jupiter 6. Поэтому не используем `@SpringBootTest`/SpringExtension под JUnit 5:
  запускаем настоящее приложение программно и проверяем через JDK HttpClient.
  Spring Boot Test доступен для вспомогательных средств, не требующих SpringExtension.
- [Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html):
  настоящий PostgreSQL, без H2 и без skip при отсутствии Docker.
- [JaCoCo Gradle](https://docs.gradle.org/current/userguide/jacoco_plugin.html):
  один test task, общий отчёт Java/Kotlin и отдельные проверки четырёх доменов.

Проверка документации не заменяет разрешение артефактов и запуск сборки.
Артефакты разрешены; `clean check bootJar` выполнен успешно 2026-10-31.


Spring Boot 4 включает health probes по умолчанию. Для текущего единственного
health endpoint они явно отключены (`management.endpoint.health.probes.enabled=false`),
чтобы сохранить ответ только со статусом. См. [официальное руководство миграции](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).
Тест передаёт порт через аргумент `--server.port=0`, поскольку
[аргументы командной строки имеют приоритет над YAML](https://docs.spring.io/spring-boot/reference/features/external-config.html).


## Этап 2: password encoder и JWT

Добавлены `spring-security-oauth2-jose` под BOM Boot 4.0.9 и
`org.bouncycastle:bcprov-jdk18on:1.86` (фиксированная версия для Argon2id).
Артефакт BC 1.86 проверен в Maven Central, Java 8+ совместима с JDK 21.
Исходно проверенный 1.83 заменён актуальным 1.86 до завершения этапа;
после замены полный clean check bootJar проходит. Самописной криптографии нет.

Argon2PasswordEncoder явно использует (16,32,1,19456,2), без более слабых defaults;
DelegatingPasswordEncoder сохраняет `{argon2id}`. NimbusJwtEncoder использует
OctetSequenceKey с kid и HS256, NimbusJwtDecoder принимает только HS256.

Источники: [password storage Spring Security](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html),
[JWT Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
[Bouncy Castle releases](https://www.bouncycastle.org/download/bouncy-castle-java/),
[BC 1.86 Maven POM](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.86/bcprov-jdk18on-1.86.pom).
