package ru.potekhincode.order.stream;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import ru.potekhincode.order.grpc.OrderStatusUpdate;

@Component
public class OrderStatusEventBus {
    private final Sinks.Many<OrderStatusUpdate> sink =
            Sinks.many().multicast().onBackpressureBuffer();

    public void publish(OrderStatusUpdate update) {
        sink.tryEmitNext(update);
    }

    public Flux<OrderStatusUpdate> stream() {
        return sink.asFlux();
    }

    /** Число активных подписчиков шины (открытых gRPC-стримов). Для тестов и метрик. */
    public int currentSubscriberCount() {
        return sink.currentSubscriberCount();
    }
}
