# Architecture Decision Records

Журнал архитектурных решений: что решили, **почему**, какие варианты отклонили и чем за выбор
платим. Пересмотренное решение не переписывается — к нему добавляется пометка со ссылкой на новое.

Формат записи: **задача → что решили → почему не иначе → чем платим**.

## Данные и события

| № | Решение | Суть |
| --- | --- | --- |
| [0001](0001-multi-item-orders.md) | Заказ из нескольких позиций | корзина вместо одного товара; цена фиксируется снимком на момент заказа |
| [0002](0002-generic-outbox-avro-producer.md) | Обобщённый Outbox-продьюсер | один `KafkaTemplate<SpecificRecord>` на все события вместо типизированных |
| [0009](0009-self-contained-order-status-changed.md) | Самодостаточное `OrderStatusChanged` | событие несёт `userId` и публикуется на **каждый** переход, а не только при отмене |
| [0018](0018-email-confirmation-delivery.md) | Доставка токена подтверждения | отдельный топик `user.confirmation-requested`, а не поле в `user.created` |
| [0021](0021-outbox-concurrency-and-reserve-idempotency.md) | Дубли резерва | две реплики поллера отправляли одни события → товар списывался дважды; `SKIP LOCKED` + журнал резервов |

## Заказ и склад

| № | Решение | Суть |
| --- | --- | --- |
| [0017](0017-product-price-grpc.md) | Цены в заказе | gRPC-запрос к каталогу вместо дублирования цен в order-service |
| [0022](0022-order-lifecycle-completion.md) | Заказ доходит до конца | `pay`/`ship`/`complete`; отгрузка — по роли, оплата — по владению; `Release` удалён |

## Безопасность и доступ

| № | Решение | Суть |
| --- | --- | --- |
| [0004](0004-oauth-login-identity-linking.md) | Вход через Google и GitHub | одна личность на email; токен отдаётся JSON'ом, а не редиректом |
| [0005](0005-rbac-ownership-role-propagation.md) | RBAC | владелец роли — user-service, распространение событием `user.role-changed` |
| [0006](0006-jwks-endpoint-key-distribution.md) | Раздача публичного ключа | JWKS-endpoint вместо копирования ключа в каждый сервис |
| [0008](0008-grpc-entrypoint-and-order-ownership.md) | gRPC-entrypoint и владение заказом | gRPC мимо шлюза (трейлеры HTTP/2); единое правило «владелец или ADMIN» для REST и gRPC |
| [0013](0013-grpc-tls-mtls.md) | TLS 1.3 для gRPC | mTLS внутри периметра, односторонний TLS + JWT на внешнем входе |
| [0019](0019-inventory-rest-api-and-jwt.md) | REST-API остатков | `PUT /inventory/{id}` под `ROLE_ADMIN`; отказ от «security by obscurity» |

## Устойчивость

| № | Решение | Суть |
| --- | --- | --- |
| [0007](0007-rate-limiting-resilience.md) | Rate-limiting на границе | свой лимитер: при отказе Redis — деградация на локальное ведро, а не тихий fail-open |
| [0010](0010-email-rate-limit-fail-open.md) | Анти-спам писем | leaky bucket в Redis; здесь **fail-open** — зеркально решению 0007, и объяснено почему |
| [0003](0003-grpc-order-status-streaming.md) | Стриминг статусов заказа | in-memory Reactor Sinks; ограничение — только для одного экземпляра |

## Наблюдаемость

| № | Решение | Суть |
| --- | --- | --- |
| [0011](0011-log-collection-file-promtail.md) | Сбор логов | JSON в файл + Promtail вместо отправки прямо из приложения *(в k8s — известный долг)* |
| [0012](0012-outbox-trace-propagation.md) | Трейс сквозь Outbox | `traceparent` в колонке таблицы — иначе трейс рвётся на асинхронной границе |

## Поставка

| № | Решение | Суть |
| --- | --- | --- |
| [0014](0014-jacoco-coverage-gate.md) | Порог покрытия | INSTRUCTION ≥ 80 % per-module; Lombok-код исключён через `lombok.config` |
| [0015](0015-helm-generic-chart.md) | Helm-чарты | один generic-чарт с параметрами вместо семи почти одинаковых |
| [0016](0016-k3d-infra-manifests-and-observability-flag.md) | Инфраструктура umbrella-чарта | свои манифесты вместо Bitnami *(observability за флагом — не реализована, см. пометку)* |
| [0020](0020-k3d-in-ci.md) | Helm-релиз в CI | настоящий k3d в раннере вместо `helm lint`; секреты без sealed-secrets |

---

**Пересмотренные решения:** 0008, 0013 и 0019 помечены об удалении gRPC-метода `Release`
(отменён в 0022); 0016 — о нереализованной части. Сами решения не переигрываются задним числом:
запись фиксирует, что думали в тот момент, а не текущее состояние кода.
