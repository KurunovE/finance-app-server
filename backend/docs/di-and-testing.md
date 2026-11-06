# DI, builders и тестовые контексты

По указанию пользователя стек переведён на Spring Boot 3.5.16 / Spring Framework 6.2.19,
с сохранением Java 21 и JUnit 5.13.4. Это позволяет использовать стандартные
SpringExtension, @SpringBootTest и @WebMvcTest. Boot/BOM остаются согласованными;
Jackson 3 заменён управляемым Jackson 2, Testcontainers использует BOM Boot 3.5.
SQL миграций и внешний JSON-контракт не менялись.

## Внедрение зависимостей

Сервисы, контроллеры, репозитории, mapper и security-фильтр — Spring beans с
конструкторным DI. Зависимости между прикладными слоями типизированы интерфейсами;
реализации сервисов и репозиториев находятся в пакетах `impl`. Каждый REST-контроллер
реализует отдельный интерфейс контракта; интерфейсы оставлены без Swagger-аннотаций.
HTTP mappings и аннотации параметров (`@RequestBody`, `@PathVariable`,
`@RequestParam`, `@RequestHeader`, `@AuthenticationPrincipal`, `@Valid`) объявлены
в интерфейсах контроллеров. Ограничения полей (`@NotNull`, `@Size` и другие)
остаются в DTO. Реализации содержат вызовы сервисов и возвращают `ResponseEntity<T>`
с прежними HTTP-статусами; для пустого ответа используется `ResponseEntity<Void>`. Для простых конструкторов применяется Lombok @RequiredArgsConstructor.
Один конструктор Spring внедряет без лишнего @Autowired; в тестовых контекстах поля
получают зависимости через @Autowired и @MockitoBean.

TransactionTemplate создаются в @Configuration/@Bean и внедряются с @Qualifier:
writeTransaction и syncReadTransaction (read-only, REPEATABLE_READ). Программные
границы транзакций сохранены: отдельный commit каждой sync-операции, commit отзыва
семьи перед 401 при refresh replay. Общий @Transactional на HTTP batch эти гарантии
изменил бы, поэтому он не добавлен.

Миграционный JdbcTemplate/recovery repository/transaction также создаются Spring в
MigrationConfiguration. Их defaultCandidate=false и @Qualifier отделяют привилегированный
пул от runtime. Guards реализуют InitializingBean и запускаются после Flyway.
ApiAuthenticationFilter внедряется в SecurityFilterChain; его отдельная servlet
регистрация явно отключена, чтобы фильтр не выполнялся дважды.

Java DTO, Entity и настройки создаются через Lombok @Builder; сохранены record-валидация,
Jackson binding и redacted toString для секретов. Все DTO, включая MutationDto, объявлены
Java records с @Builder. Kotlin вызывает record-конструктор напрямую, поэтому
kotlin-lombok compiler plugin не используется. В Java используются record-accessors
status()/version()/snapshot()/code() вместо JavaBean getters.
Lombok-generated builders естественно имеют сгенерированные служебные вложенные типы;
вложенных типов, написанных вручную, больше нет. Генерируемый код помечен @Generated;
пороги покрытия production/business кода не снижены.

JDK/Spring/библиотечные объекты (коллекции, исключения JDK, JDBC adapters, потоки,
TransactionTemplate) создаются обычными API; Lombok builder к чужим классам не применяется.
Создание инфраструктурных зависимостей локализовано в @Bean factories. В unit-тестах
сам объект тестирования может создаваться напрямую, зависимости подменяются Mockito;
модели и тестовые аккаунты создаются builders.

## Проверки

- FinanceServiceTest и RecoveryStateTest используют @ExtendWith(MockitoExtension.class),
  @Mock, @InjectMocks, @Captor. Самописные StubRepository удалены. Проверяются результаты,
  отсутствие writes при отказах и реальные параметры вызова репозитория.
- AccountMailTest использует JavaMailSender mock; RateLimitsTest — Clock/AuthSettings mocks.
  Криптография и чистая валидация проверяются настоящими реализациями.
- AuthControllerSliceTest использует @WebMvcTest, реальную MVC validation/security цепочку
  и @MockitoBean сервисы. Проверяются регистрация, ошибки, удаление по `/v1/me`
  и отсутствие стандартного UserDetailsService. Исключение его автоконфигурации
  берётся из application.yml, отдельного тестового исключения нет.
  В этом контексте нет PostgreSQL/SMTP/Flyway.
- ServiceWiringTest использует @SpringBootTest с явной конфигурацией только финансовых
  сервисов и mock-репозиторием. Проверяется отсутствие DataSource и HTTP-контроллеров.
- FinanceIntegrationTest использует @SpringBootTest с отдельным JDBC-контекстом: только
  finance/repository/RLS и PostgreSQL. Auth/mail/web не загружаются.
- Полные HTTP/restore/proxy тесты сохранены: им нужны настоящий web server и управляемый
  restart/restore, поэтому их fixtures продолжают запускать полноценное приложение.

WireMock не добавлен: у backend нет исходящего HTTP-провайдера, который надо подменять.
SMTP проверяется реальным локальным Mailpit и Mockito в unit-тестах, без внешних писем.

Каждый @Test/@ParameterizedTest имеет короткий русский @DisplayName. Параметризованные
случаи отображают русское имя и индекс; секреты/полный payload в имени не показываются.
Общий test, отдельные unitTest/integrationTest и прежние четыре доменных/общий gates 80%
сохраняются. Proxy mode выставлен в JDK, чтобы Spring проксировал bean через его
интерфейс и мог сохранить final у JDBC-реализаций. CI не запускается, проверки локальные.
