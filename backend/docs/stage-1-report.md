# Этап 1 — схема, роли и RLS

Дата: 2026-10-31. Пользователь разрешил переход к этапу 1 и исключил CI.
Локальные критерии этапа 1 выполнены на JDK 21 и PostgreSQL 18.3 в Docker.

Реализовано:

- Bootstrap psql создаёт finance_migrator, finance_runtime и закрытую схему app.
  Пароли — окружение, никаких реальных credentials в файлах проекта.
- Flyway V1 создаёт users, auth_sessions, used_refresh_tokens, categories,
  expenses, monthly_budgets, category_budgets, user_sync_state, sync_operations,
  sync_changes; PK/UNIQUE/CHECK/составные FK, индексы из ТЗ.
- ENABLE/FORCE RLS для семи финансовых и sync-таблиц. Без контекста — default deny.
  Runtime не владелец и не привилегированная роль; не может менять схему,
  отключать RLS, TRUNCATE, создавать temp tables или читать историю Flyway.
- UserTransactionContext устанавливает UUID через параметризованный
  transaction-local set_config на соединении активной транзакции своего datasource.
  Отвергает null, отсутствие транзакции, другой datasource и повторную установку.
- HTTP smoke теперь использует настоящие runtime/migrator credentials.
  Готовый backend сохраняет denyAll для не реализованных бизнес-маршрутов.

Фактические проверки:

| Требование | Проверка и результат |
|---|---|
| DB-01 | Flyway мигрирует пустую БД, создаёт 10 таблиц, политики и права |
| DB-02 | Повтор migrate выполняет 0 миграций; приложение перезапускается на той же БД |
| ISO-03 | FK отвергает чужую категорию в расходе и category budget, SQLSTATE 23503 |
| ISO-05 | A/B/A через пул из одного соединения; pg_backend_pid неизменен |
| ISO-06 | SELECT без WHERE видит только текущего пользователя; UPDATE/DELETE не меняют чужие строки; INSERT чужого user_id запрещён на всех 7 таблицах |
| ISO-07 | Нет контекста — 0 строк; commit, rollback и read-only не оставляют контекст |
| CHECK/FK/UNIQUE | Нулевые/отрицательные суммы, версии, не начало месяца, длины, BIGINT overflow, уникальность названий/бюджетов/email/seq, формат sync payload и tombstones |
| Atomic rollback | При ошибке расхода откатываются новый users, категории, state и sync_changes |
| Propagation | REQUIRED не меняет внешний контекст; REQUIRES_NEW изолирован и восстанавливает внешний |
| FORCE RLS | Migrator-владелец без контекста не видит существующую финансовую строку |

`clean check bootJar --no-daemon`: **BUILD SUCCESSFUL**, 46 тестов,
0 failures/errors/skips. JaCoCo LINE: overall 22/24 (91,67%), security 8/8 (100%),
persistence 13/13 (100%). Штатные пороги 80% сохранены, исключения не добавлены.
Compose проверен командой config --quiet с неперсональными placeholder значениями.
Готовый bootJar дважды запущен с профилем local на одной новой БД с bootstrap
из infra: миграции под finance_migrator, pool под finance_runtime. Оба раза
health вернул HTTP 200 {"status":"UP"}. Логи — build/stage-1-bootjar-{1,2}.log.
Проверочные процессы остановлены, временный контейнер удалён.

Java/YAML отформатированы через IntelliJ. Общий SQL-форматтер IDE оказался
несовместим с метакомандами psql; bootstrap восстановлен, после чего выполнен
полный успешный прогон на новых контейнерах. Миграции проверены Flyway на PostgreSQL.

Существующие тома и данные пользователя не удалялись. Документирована однократная
подготовка ролей для тома этапа 0. CI, remote и deployment не использовались.
Auth-репозитории, нормализация email и category names, проверки архивной категории,
смены валюты, token auth и sync-сервисы не реализованы на этом этапе.

Следующий этап: авторизация и управление сессиями, раздел 13 ТЗ.
