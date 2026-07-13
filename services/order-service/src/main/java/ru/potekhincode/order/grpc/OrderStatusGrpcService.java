package ru.potekhincode.order.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import reactor.core.Disposable;
import ru.potekhincode.order.exception.OrderAccessDeniedException;
import ru.potekhincode.order.exception.OrderNotFoundException;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OrderStatus;
import ru.potekhincode.order.security.Caller;
import ru.potekhincode.order.service.OrderService;
import ru.potekhincode.order.stream.OrderStatusEventBus;

import java.util.UUID;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class OrderStatusGrpcService extends OrderStatusServiceGrpc.OrderStatusServiceImplBase {

    private final OrderStatusEventBus eventBus;
    private final OrderService orderService;

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

        Caller caller = new Caller(JwtServerInterceptor.USER_ID.get(), JwtServerInterceptor.ROLE.get());

        Order order;
        try {
            order = orderService.requireVisible(orderId, caller);
        } catch (OrderNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .asRuntimeException());
            return;
        } catch (OrderAccessDeniedException e) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription(e.getMessage())
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
