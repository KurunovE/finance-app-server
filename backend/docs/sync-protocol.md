# Финансовая модель и синхронизация

Реализованы этапы 3–5. Java-контроллеры/транзакции вызывают Kotlin FinanceRules /
FinanceService и Java SQL repository. Все SQL фильтруются по подтверждённому user_id;
RLS устанавливается один раз на транзакцию на том же datasource.

POST /api/v1/sync/push: `{ "operations": [...] }`, 1–50 операций, до 256 KiB JSON,
глубина data <=8. Поля operationId/entityType/entityId/action/epoch обязательны.
X-Recovery-Epoch обязателен; до обычного sync устройство проходит full replay/ack.
Типы: CATEGORY, EXPENSE, MONTHLY_BUDGET, CATEGORY_BUDGET; действия CREATE/UPDATE/DELETE.
CREATE имеет expectedVersion=null, UPDATE/DELETE — положительную текущую версию.

CREATE требует name у категории; categoryId/amountMinor/occurredOn у расхода;
monthStart/limitMinor у общего бюджета; дополнительно categoryId у бюджета категории.
UPDATE принимает непустой частичный data: остальные поля сохраняются. note=null
очищает заметку. DELETE имеет null/пустой data. Категории архивируются UPDATE archived=true,
их DELETE запрещён. Архивирование не удаляет историю; новые расходы и смена категории
на архивную запрещены, заметку/сумму исторического расхода редактировать можно.

Названия: trim + NFKC; normalized_name — lower(Locale.ROOT). Уникальность на аккаунт.
Заметки trim, пустая становится null; пределы в Unicode code points 80/500.
Деньги — положительный int64, дроби/строки/overflow отклоняются. Даты — YYYY-MM-DD,
годы 0001–9999, реальная календарная дата; monthStart — первое число месяца.
SUM(bigint) в PostgreSQL numeric; при преобразовании в long требуется exact check.
Аналитического HTTP API и float/double денег нет.

Одна операция — одна транзакция. Порядок: shared recovery fence, проверка epoch/устройства, проверка живой auth-сессии и блокировка
users, установка RLS, user_sync_state FOR UPDATE, проверка idempotency,
проверки бизнес-правил/версии, mutation, last_seq+1 и sync_changes, sync_operations, commit.
Блокировки одного аккаунта не блокируют другие аккаунты. При технической DB-ошибке
вся операция откатывается; это проверяется ошибкой триггера после изменения расхода.
Бизнес-отказы сохраняются как детерминированный результат без продвижения seq.

request_hash — SHA-256 канонического JSON с сортировкой ключей, нормализованным data,
entityType/entityId/action/expectedVersion/epoch. Ключ (user_id,operation_id).
Совпавший успешный повтор даёт ALREADY_APPLIED с исходной версией/snapshot;
совпавший отказ возвращает исходный отказ. Другой payload — HTTP 409 OPERATION_ID_REUSED.
Для исправленного payload нужен новый operationId. В пакете уже закоммиченные операции
не откатываются из-за последующего конфликта/технического отказа; повтор пакета безопасен
при сохранении operationId. Клиент не должен предполагать атомарность всей пачки.

UPDATE с неверной версией возвращает CONFLICT с собственной актуальной записью.
Чужая и отсутствующая запись одинаково NOT_FOUND, без snapshot. Конфликт логического
ключа возвращает собственную каноническую запись. DELETE создаёт tombstone с версией
предыдущая+1; повторное CREATE того же удалённого entityId запрещено ENTITY_ID_REUSED.
Версия Long.MAX_VALUE не переполняется: операция отвергается.

GET /api/v1/sync/pull?cursor=0&limit=100: cursor>=0, limit 1–200. Одна read-only
REPEATABLE_READ транзакция: last_seq и страница из limit+1 событий принадлежат
одному snapshot. nextCursor — последний выданный seq (пустая страница сохраняет cursor),
hasMore — наличие дополнительного события. Cursor выше last_seq отклоняется.
События идут строго по seq, без offset и глобального sequence. DELETE snapshot=null.
Клиент сохраняет страницу и cursor атомарно локально; outbox не удаляет при 401/сбое.

Результаты операций и журнал не очищаются. Регистрация создаёт три персональные
категории и события seq=1..3. Удаление всего аккаунта — отдельное явное исключение,
оно каскадно удаляет весь его sync state/journals.

Критические сценарии проверены реальным HTTP/PostgreSQL: 103 события по limit=1,
два устройства, разные аккаунты, параллельный CREATE и stale UPDATE, rollback,
идемпотентный повтор после условной потери ответа, tombstones и пакет success/conflict.
Потеря ответа моделируется отбрасыванием результата уже завершённого первого запроса,
не реальной потерей пакетов сети. Политика восстановления старого backup — в operations.md.

Обязательная новая последовательность bootstrap/recovery и сохранения старых локальных данных:
[recovery-protocol.md](recovery-protocol.md). Pull дополнительно возвращает epoch.
