-- проброс контекста трейса сквозь Outbox.
-- Храним W3C traceparent исходного запроса, чтобы Kafka-публикация из поллера
-- продолжала тот же distributed trace, что и POST /orders.
ALTER TABLE outbox ADD COLUMN trace_context VARCHAR(64);
