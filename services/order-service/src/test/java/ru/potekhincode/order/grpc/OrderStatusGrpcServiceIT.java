package ru.potekhincode.order.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OrderStatus;
import ru.potekhincode.order.repository.OrderRepository;
import ru.potekhincode.order.stream.OrderStatusEventBus;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Изолированный IT gRPC server-streaming: in-process сервер только с
 * {@link OrderStatusGrpcService}, {@link OrderRepository} под Mockito, реальная
 * {@link OrderStatusEventBus}. Не поднимает Spring/Kafka/PG — проверяется именно
 * логика стрима (snapshot → push → закрытие на терминальном статусе, фильтр по
 * orderId, ошибки).
 */
class OrderStatusGrpcServiceIT {

    private static final String SERVER_NAME = "order-status-grpc-it";
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private OrderRepository orderRepository;
    private OrderStatusEventBus eventBus;
    private Server server;
    private ManagedChannel channel;
    private OrderStatusServiceGrpc.OrderStatusServiceStub stub;

    @BeforeEach
    void setUp() throws Exception {
        orderRepository = mock(OrderRepository.class);
        eventBus = new OrderStatusEventBus();

        server = InProcessServerBuilder.forName(SERVER_NAME)
                .directExecutor()
                .addService(new OrderStatusGrpcService(orderRepository, eventBus))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
        stub = OrderStatusServiceGrpc.newStub(channel);
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

    @Test
    void streamEmitsCurrentStatusAsInitialSnapshot() throws Exception {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.NEW)));

        Collector collector = subscribe(ORDER_ID);

        assertThat(collector.poll().getStatus()).isEqualTo("NEW");
    }

    @Test
    void streamPushesTransitionsAndCompletesOnTerminalStatus() throws Exception {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.NEW)));

        Collector collector = subscribe(ORDER_ID);
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
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.NEW)));

        Collector collector = subscribe(ORDER_ID);
        assertThat(collector.poll().getStatus()).isEqualTo("NEW");

        awaitSubscribed();
        eventBus.publish(update(OTHER_ID, OrderStatus.RESERVED));   // чужой заказ — должен быть отфильтрован
        eventBus.publish(update(ORDER_ID, OrderStatus.CANCELLED));

        assertThat(collector.poll().getStatus()).isEqualTo("CANCELLED");
        await().atMost(2, TimeUnit.SECONDS).untilTrue(collector.completed);
    }

    @Test
    void streamClosesImmediatelyWhenOrderAlreadyTerminal() throws Exception {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CANCELLED)));

        Collector collector = subscribe(ORDER_ID);

        assertThat(collector.poll().getStatus()).isEqualTo("CANCELLED");
        await().atMost(2, TimeUnit.SECONDS).untilTrue(collector.completed);
        assertThat(eventBus.currentSubscriberCount()).isZero();   // на шину не подписывались
    }

    @Test
    void streamFailsWithNotFoundForUnknownOrder() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        Collector collector = subscribe(ORDER_ID);

        await().atMost(2, TimeUnit.SECONDS).until(() -> collector.error.get() != null);
        assertThat(Status.fromThrowable(collector.error.get()).getCode())
                .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void streamFailsWithInvalidArgumentForMalformedId() {
        Collector collector = new Collector();
        stub.streamOrderStatus(StreamOrderStatusRequest.newBuilder()
                .setOrderId("not-a-uuid").build(), collector);

        await().atMost(2, TimeUnit.SECONDS).until(() -> collector.error.get() != null);
        assertThat(Status.fromThrowable(collector.error.get()).getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    // --- helpers ---

    private Collector subscribe(UUID orderId) {
        Collector collector = new Collector();
        stub.streamOrderStatus(StreamOrderStatusRequest.newBuilder()
                .setOrderId(orderId.toString()).build(), collector);
        return collector;
    }

    private void awaitSubscribed() {
        await().atMost(2, TimeUnit.SECONDS).until(() -> eventBus.currentSubscriberCount() >= 1);
    }

    private Order order(OrderStatus status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId("u-1");
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
