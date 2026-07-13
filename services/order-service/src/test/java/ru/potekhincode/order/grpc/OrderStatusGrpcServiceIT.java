package ru.potekhincode.order.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import ru.potekhincode.order.exception.OrderAccessDeniedException;
import ru.potekhincode.order.exception.OrderNotFoundException;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OrderStatus;
import ru.potekhincode.order.security.Caller;
import ru.potekhincode.order.service.OrderService;
import ru.potekhincode.order.stream.OrderStatusEventBus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Изолированный IT gRPC server-streaming: in-process сервер с {@link OrderStatusGrpcService}
 * за {@link JwtServerInterceptor}, {@link OrderService} под Mockito, реальная
 * {@link OrderStatusEventBus}. Spring/Kafka/PG не поднимаются.
 * <p>
 * Проверяется и логика стрима (snapshot → push → закрытие на терминальном статусе, фильтр
 * по orderId — этап 4.7, ADR 0003), и защита gRPC-entrypoint'а (шаг 6.3, ADR 0008):
 * без токена — UNAUTHENTICATED, чужой заказ — PERMISSION_DENIED.
 * <p>
 * Интерсептор регистрируется вручную через {@link ServerInterceptors}: аннотация
 * {@code @GrpcGlobalServerInterceptor} работает только в живом контексте net.devh,
 * а здесь сервер поднимается тестом.
 */
class OrderStatusGrpcServiceIT {

    private static final String SERVER_NAME = "order-status-grpc-it";
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String OWNER_ID = "u-1";
    private static final String STRANGER_ID = "u-99";

    private OrderService orderService;
    private JwtDecoder jwtDecoder;
    private OrderStatusEventBus eventBus;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        orderService = mock(OrderService.class);
        jwtDecoder = mock(JwtDecoder.class);
        eventBus = new OrderStatusEventBus();

        server = InProcessServerBuilder.forName(SERVER_NAME)
                .directExecutor()
                .addService(ServerInterceptors.intercept(
                        new OrderStatusGrpcService(eventBus, orderService),
                        new JwtServerInterceptor(jwtDecoder)))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // --- аутентификация и авторизация gRPC-entrypoint'а (шаг 6.3) ---

    @Test
    void streamWithoutTokenIsUnauthenticated() {
        Collector collector = new Collector();
        OrderStatusServiceGrpc.newStub(channel).streamOrderStatus(request(ORDER_ID), collector);

        assertThat(codeOf(collector)).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void streamWithInvalidTokenIsUnauthenticated() {
        when(jwtDecoder.decode("garbage")).thenThrow(new BadJwtException("bad signature"));

        Collector collector = new Collector();
        stubWithRawToken("garbage").streamOrderStatus(request(ORDER_ID), collector);

        assertThat(codeOf(collector)).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void streamOfForeignOrderIsPermissionDenied() {
        when(orderService.requireVisible(eq(ORDER_ID), any(Caller.class)))
                .thenThrow(new OrderAccessDeniedException(ORDER_ID));

        Collector collector = subscribe(ORDER_ID, STRANGER_ID, "ROLE_USER");

        assertThat(codeOf(collector)).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    /**
     * Личность из токена доезжает до сервисного слоя через io.grpc.Context (не ThreadLocal —
     * иначе в стриме, где колбэки идут из чужих потоков, было бы пусто).
     */
    @Test
    void callerIsTakenFromTokenAndPassedToService() throws Exception {
        when(orderService.requireVisible(eq(ORDER_ID), any(Caller.class)))
                .thenReturn(order(OrderStatus.CANCELLED, OWNER_ID));

        Collector collector = subscribe(ORDER_ID, OWNER_ID, "ROLE_USER");
        assertThat(collector.poll().getStatus()).isEqualTo("CANCELLED");

        ArgumentCaptor<Caller> captor = ArgumentCaptor.forClass(Caller.class);
        verify(orderService).requireVisible(eq(ORDER_ID), captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(OWNER_ID);
        assertThat(captor.getValue().role()).isEqualTo("ROLE_USER");
    }

    // --- логика стрима (этап 4.7, ADR 0003) ---

    @Test
    void streamEmitsCurrentStatusAsInitialSnapshot() throws Exception {
        stubOrder(OrderStatus.NEW);

        Collector collector = subscribe(ORDER_ID, OWNER_ID, "ROLE_USER");

        assertThat(collector.poll().getStatus()).isEqualTo("NEW");
    }

    @Test
    void streamPushesTransitionsAndCompletesOnTerminalStatus() throws Exception {
        stubOrder(OrderStatus.NEW);

        Collector collector = subscribe(ORDER_ID, OWNER_ID, "ROLE_USER");
        assertThat(collector.poll().getStatus()).isEqualTo("NEW");          // snapshot

        awaitSubscribed();
        eventBus.publish(update(ORDER_ID, OrderStatus.RESERVED));
        assertThat(collector.poll().getStatus()).isEqualTo("RESERVED");     // push

        eventBus.publish(update(ORDER_ID, OrderStatus.CANCELLED));
        assertThat(collector.poll().getStatus()).isEqualTo("CANCELLED");    // terminal

        await().atMost(2, TimeUnit.SECONDS).untilTrue(collector.completed);
    }

    @Test
    void streamIgnoresUpdatesForOtherOrders() throws Exception {
        stubOrder(OrderStatus.NEW);

        Collector collector = subscribe(ORDER_ID, OWNER_ID, "ROLE_USER");
        assertThat(collector.poll().getStatus()).isEqualTo("NEW");

        awaitSubscribed();
        eventBus.publish(update(OTHER_ID, OrderStatus.RESERVED));   // чужой заказ — должен быть отфильтрован
        eventBus.publish(update(ORDER_ID, OrderStatus.CANCELLED));

        assertThat(collector.poll().getStatus()).isEqualTo("CANCELLED");
        await().atMost(2, TimeUnit.SECONDS).untilTrue(collector.completed);
    }

    @Test
    void streamClosesImmediatelyWhenOrderAlreadyTerminal() throws Exception {
        stubOrder(OrderStatus.CANCELLED);

        Collector collector = subscribe(ORDER_ID, OWNER_ID, "ROLE_USER");

        assertThat(collector.poll().getStatus()).isEqualTo("CANCELLED");
        await().atMost(2, TimeUnit.SECONDS).untilTrue(collector.completed);
        assertThat(eventBus.currentSubscriberCount()).isZero();   // на шину не подписывались
    }

    @Test
    void streamFailsWithNotFoundForUnknownOrder() {
        when(orderService.requireVisible(eq(ORDER_ID), any(Caller.class)))
                .thenThrow(new OrderNotFoundException(ORDER_ID));

        Collector collector = subscribe(ORDER_ID, OWNER_ID, "ROLE_USER");

        assertThat(codeOf(collector)).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void streamFailsWithInvalidArgumentForMalformedId() {
        Collector collector = new Collector();
        stubWithToken(OWNER_ID, "ROLE_USER").streamOrderStatus(
                StreamOrderStatusRequest.newBuilder().setOrderId("not-a-uuid").build(), collector);

        assertThat(codeOf(collector)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    // --- helpers ---

    private void stubOrder(OrderStatus status) {
        when(orderService.requireVisible(eq(ORDER_ID), any(Caller.class)))
                .thenReturn(order(status, OWNER_ID));
    }

    private Collector subscribe(UUID orderId, String subject, String role) {
        Collector collector = new Collector();
        stubWithToken(subject, role).streamOrderStatus(request(orderId), collector);
        return collector;
    }

    /** Стаб с токеном, который мок-декодер признаёт валидным (sub/role — как в реальном JWT). */
    private OrderStatusServiceGrpc.OrderStatusServiceStub stubWithToken(String subject, String role) {
        String token = "test-" + role + "-" + subject;
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(subject)
                .claim("role", role)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode(token)).thenReturn(jwt);
        return stubWithRawToken(token);
    }

    private OrderStatusServiceGrpc.OrderStatusServiceStub stubWithRawToken(String token) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return OrderStatusServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private StreamOrderStatusRequest request(UUID orderId) {
        return StreamOrderStatusRequest.newBuilder().setOrderId(orderId.toString()).build();
    }

    private Status.Code codeOf(Collector collector) {
        await().atMost(2, TimeUnit.SECONDS).until(() -> collector.error.get() != null);
        return Status.fromThrowable(collector.error.get()).getCode();
    }

    private Order order(OrderStatus status, String ownerId) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(ownerId);
        order.setStatus(status);
        order.setUpdatedAt(OffsetDateTime.now());
        return order;
    }

    private OrderStatusUpdate update(UUID orderId, OrderStatus status) {
        return OrderStatusUpdate.newBuilder()
                .setOrderId(orderId.toString())
                .setStatus(status.name())
                .setTimestamp(OffsetDateTime.now().toString())
                .build();
    }

    private void awaitSubscribed() {
        await().atMost(2, TimeUnit.SECONDS).until(() -> eventBus.currentSubscriberCount() >= 1);
    }

    /** Накапливает элементы стрима в очередь, фиксирует onError/onCompleted. */
    private static final class Collector implements StreamObserver<OrderStatusUpdate> {
        private final BlockingQueue<OrderStatusUpdate> queue = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final AtomicBoolean completed = new AtomicBoolean(false);

        @Override
        public void onNext(OrderStatusUpdate value) {
            queue.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
        }

        @Override
        public void onCompleted() {
            completed.set(true);
        }

        OrderStatusUpdate poll() throws InterruptedException {
            OrderStatusUpdate value = queue.poll(2, TimeUnit.SECONDS);
            assertThat(value).as("ожидался элемент стрима в течение 2с").isNotNull();
            return value;
        }
    }
}
