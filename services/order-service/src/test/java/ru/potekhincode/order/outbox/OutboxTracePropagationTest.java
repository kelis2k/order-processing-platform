package ru.potekhincode.order.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OrderStatus;
import ru.potekhincode.order.model.OutboxEvent;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip проброса трейса сквозь Outbox (Шаг 7.4, ADR 0012):
 * фабрика кладёт W3C traceparent активного span'а в строку outbox, поллер
 * восстанавливает из него Context. Агента здесь нет — вместо него настраиваем
 * GlobalOpenTelemetry с W3C-пропагатором (тот же, что агент ставит в проде).
 */
class OutboxTracePropagationTest {

    // валидные W3C идентификаторы (пример из спецификации W3C Trace Context)
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";

    private final OutboxEventFactory factory = new OutboxEventFactory(new ObjectMapper());
    private final OutboxPoller poller = new OutboxPoller(null, null, null);

    @BeforeAll
    static void installPropagator() {
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetry.propagating(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance())));
    }

    @AfterAll
    static void reset() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void factory_stores_traceparent_of_active_span() {
        try (Scope ignored = Context.root().with(Span.wrap(sampledSpanContext())).makeCurrent()) {
            OutboxEvent event = factory.orderStatusChanged(newOrder(), "reserved");

            assertThat(event.getTraceContext()).isEqualTo(TRACEPARENT);
        }
    }

    @Test
    void factory_stores_null_when_no_active_span() {
        // вне span'а (root-контекст) пропагатору нечего инжектить → traceparent отсутствует
        OutboxEvent event = factory.orderStatusChanged(newOrder(), "reserved");

        assertThat(event.getTraceContext()).isNull();
    }

    @Test
    void poller_restores_span_context_from_stored_traceparent() {
        OutboxEvent event = new OutboxEvent();
        event.setTraceContext(TRACEPARENT);

        SpanContext restored = Span.fromContext(poller.restoreContext(event)).getSpanContext();

        assertThat(restored.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(restored.getSpanId()).isEqualTo(SPAN_ID);
        assertThat(restored.isRemote()).isTrue();   // контекст пришёл «извне» (из строки outbox)
    }

    @Test
    void poller_falls_back_to_current_when_traceContext_null() {
        OutboxEvent event = new OutboxEvent();   // traceContext == null

        // без агента/traceparent — не падает, отдаёт текущий контекст (без span'а)
        Context restored = poller.restoreContext(event);

        assertThat(Span.fromContext(restored).getSpanContext().isValid()).isFalse();
    }

    @Test
    void round_trip_factory_to_poller_preserves_ids() {
        OutboxEvent event;
        try (Scope ignored = Context.root().with(Span.wrap(sampledSpanContext())).makeCurrent()) {
            event = factory.orderStatusChanged(newOrder(), "reserved");
        }

        SpanContext restored = Span.fromContext(poller.restoreContext(event)).getSpanContext();

        assertThat(restored.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(restored.getSpanId()).isEqualTo(SPAN_ID);
    }

    private static SpanContext sampledSpanContext() {
        return SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
    }

    private static Order newOrder() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setUserId("user-1");
        order.setStatus(OrderStatus.RESERVED);
        return order;
    }
}
