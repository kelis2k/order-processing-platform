package ru.potekhincode.order.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import reactor.core.Disposable;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OrderStatus;
import ru.potekhincode.order.repository.OrderRepository;
import ru.potekhincode.order.stream.OrderStatusEventBus;

import java.util.UUID;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class OrderStatusGrpcService extends OrderStatusServiceGrpc.OrderStatusServiceImplBase {
    private final OrderRepository orderRepository;
    private final OrderStatusEventBus eventBus;

    @Override
    public void streamOrderStatus(StreamOrderStatusRequest request,
                                  StreamObserver<OrderStatusUpdate> responseObserver) {
        UUID orderId;
        try {
            orderId = UUID.fromString(request.getOrderId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid order_id: " + request.getOrderId())
                    .asRuntimeException());
            return;
        }

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Order not found: " + orderId)
                    .asRuntimeException());
            return;
        }

        // 1) initial snapshot — текущий статус из БД
        responseObserver.onNext(toUpdate(orderId.toString(), order.getStatus(),
                order.getUpdatedAt().toString()));

        // уже терминальный → закрываем поток, на шину не подписываемся
        if (order.getStatus().isTerminal()) {
            responseObserver.onCompleted();
            return;
        }

        // 2) подписка на шину, фильтр по orderId, авто-завершение на терминальном статусе
        String id = orderId.toString();
        Disposable subscription = eventBus.stream()
                .filter(u -> u.getOrderId().equals(id))
                .takeUntil(u -> OrderStatus.valueOf(u.getStatus()).isTerminal())
                .subscribe(responseObserver::onNext,
                        responseObserver::onError,
                        responseObserver::onCompleted);

        // 3) клиент отвалился → освобождаем подписку (иначе утечка)
        if (responseObserver instanceof ServerCallStreamObserver<OrderStatusUpdate> scso) {
            scso.setOnCancelHandler(subscription::dispose);
        }
    }

    private OrderStatusUpdate toUpdate(String orderId, OrderStatus status, String timestamp) {
        return OrderStatusUpdate.newBuilder()
                .setOrderId(orderId)
                .setStatus(status.name())
                .setTimestamp(timestamp)
                .build();
    }
}
