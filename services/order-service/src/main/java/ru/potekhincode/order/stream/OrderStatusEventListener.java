package ru.potekhincode.order.stream;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.potekhincode.order.event.OrderStatusChangedEvent;
import ru.potekhincode.order.grpc.OrderStatusUpdate;

@Component
@RequiredArgsConstructor
public class OrderStatusEventListener {
    private final OrderStatusEventBus eventBus;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(OrderStatusChangedEvent event) {
        eventBus.publish(OrderStatusUpdate.newBuilder()
                .setOrderId(event.orderId().toString())
                .setStatus(event.status().name())
                .setTimestamp(event.occurredAt().toString())
                .build());
    }
}
