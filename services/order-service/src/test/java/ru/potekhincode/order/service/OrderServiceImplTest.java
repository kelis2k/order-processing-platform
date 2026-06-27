package ru.potekhincode.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.potekhincode.order.client.InventoryClient;
import ru.potekhincode.order.client.UnavailableItem;
import ru.potekhincode.order.dto.request.CreateOrderRequest;
import ru.potekhincode.order.dto.request.OrderItemRequest;
import ru.potekhincode.order.dto.response.OrderResponse;
import ru.potekhincode.order.exception.InsufficientStockException;
import ru.potekhincode.order.exception.InvalidStatusTransitionException;
import ru.potekhincode.order.exception.OrderNotFoundException;
import ru.potekhincode.order.outbox.OutboxEventFactory;
import ru.potekhincode.order.mapper.OrderMapper;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OrderItem;
import ru.potekhincode.order.model.OrderStatus;
import ru.potekhincode.order.model.SagaState;
import ru.potekhincode.order.model.SagaStatus;
import ru.potekhincode.order.repository.OrderRepository;
import ru.potekhincode.order.repository.OutboxRepository;
import ru.potekhincode.order.repository.SagaStateRepository;
import ru.potekhincode.order.service.impl.OrderServiceImpl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final UUID ORDER_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID UNKNOWN_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String USER_ID    = "u-42";
    private static final String PRODUCT_ID = "p-100";
    private static final int QUANTITY      = 2;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private SagaStateRepository sagaStateRepository;

    @Mock
    private OutboxEventFactory outboxEventFactory;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    void shouldCreateOrderInNewStatusWithLinkedItems() {
        CreateOrderRequest request =
                new CreateOrderRequest(USER_ID, List.of(new OrderItemRequest(PRODUCT_ID, QUANTITY)));
        OrderItem mappedItem = new OrderItem();
        mappedItem.setProductId(PRODUCT_ID);
        mappedItem.setQuantity(QUANTITY);

        when(inventoryClient.checkAvailability(request.items())).thenReturn(List.of()); // дефицита нет
        when(orderMapper.toEntity(any(OrderItemRequest.class))).thenReturn(mappedItem);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order toSave = inv.getArgument(0);
            toSave.setId(ORDER_ID); // БД присваивает UUID при flush; в юните имитируем
            return toSave;
        });
        when(orderMapper.toResponse(any(Order.class)))
                .thenReturn(new OrderResponse(ORDER_ID, USER_ID, OrderStatus.NEW, null, List.of(), null));

        OrderResponse result = orderService.create(request);

        verify(inventoryClient).checkAvailability(request.items()); // склад опрошен перед сохранением
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        Order saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().get(0).getOrder()).isSameAs(saved); // addItem связал обе стороны
        assertThat(result.status()).isEqualTo(OrderStatus.NEW);

        // сага стартует в AWAITING_RESERVATION, событие легло в outbox (та же транзакция)
        ArgumentCaptor<SagaState> sagaCaptor = ArgumentCaptor.forClass(SagaState.class);
        verify(sagaStateRepository).save(sagaCaptor.capture());
        assertThat(sagaCaptor.getValue().getState()).isEqualTo(SagaStatus.AWAITING_RESERVATION);
        verify(outboxRepository).save(any());
    }

    @Test
    void shouldRejectAndNotSaveWhenStockInsufficient() {
        CreateOrderRequest request =
                new CreateOrderRequest(USER_ID, List.of(new OrderItemRequest(PRODUCT_ID, QUANTITY)));
        when(inventoryClient.checkAvailability(request.items()))
                .thenReturn(List.of(new UnavailableItem(PRODUCT_ID, QUANTITY, 1))); // просили 2, есть 1

        assertThatThrownBy(() -> orderService.create(request))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining(PRODUCT_ID);

        verify(orderRepository, never()).save(any()); // ← инвариант: заказ не создаётся
    }

    @Test
    void shouldReturnOrderWhenFoundById() {
        Order order = new Order();
        OrderResponse response =
                new OrderResponse(ORDER_ID, USER_ID, OrderStatus.NEW, null, List.of(), null);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderMapper.toResponse(order)).thenReturn(response);

        assertThat(orderService.findById(ORDER_ID)).isEqualTo(response);
    }

    @Test
    void shouldThrowNotFoundWhenOrderMissing() {
        when(orderRepository.findById(UNKNOWN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(UNKNOWN_ID))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining(UNKNOWN_ID.toString());
    }

    @Test
    void shouldChangeStatusWhenTransitionAllowed() {
        Order order = new Order();
        order.setStatus(OrderStatus.NEW);

        orderService.transition(order, OrderStatus.RESERVED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
    }

    @Test
    void shouldRejectTransitionAndKeepStatusWhenNotAllowed() {
        Order order = new Order();
        order.setStatus(OrderStatus.NEW);

        assertThatThrownBy(() -> orderService.transition(order, OrderStatus.SHIPPED))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW); // статус не изменился
    }

    @Test
    void shouldMoveOrderAndSagaToReservedOnSuccess() {
        SagaState saga = new SagaState();
        saga.setOrderId(ORDER_ID);
        saga.setState(SagaStatus.AWAITING_RESERVATION);
        Order order = new Order();
        order.setStatus(OrderStatus.NEW);

        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(saga));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        orderService.onInventoryReserved(ORDER_ID, true, null);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);     // FSM NEW→RESERVED
        assertThat(saga.getState()).isEqualTo(SagaStatus.RESERVED);        // сага замкнулась
    }

    @Test
    void shouldBeIdempotentWhenSagaAlreadyReserved() {
        SagaState saga = new SagaState();
        saga.setOrderId(ORDER_ID);
        saga.setState(SagaStatus.RESERVED); // повторная доставка inventory.reserved (at-least-once)

        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(saga));

        orderService.onInventoryReserved(ORDER_ID, true, null);

        verify(orderRepository, never()).findById(any()); // заказ даже не грузим
        assertThat(saga.getState()).isEqualTo(SagaStatus.RESERVED); // состояние не тронуто
    }

    @Test
    void shouldCancelOrderAndSagaAndEmitStatusChangedOnFailure() {
        SagaState saga = new SagaState();
        saga.setOrderId(ORDER_ID);
        saga.setState(SagaStatus.AWAITING_RESERVATION);
        Order order = new Order();
        order.setStatus(OrderStatus.NEW);

        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(saga));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        String reason = "p-100: запрошено 99999, доступно 10";
        orderService.onInventoryReserved(ORDER_ID, false, reason);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);   // FSM NEW→CANCELLED
        assertThat(saga.getState()).isEqualTo(SagaStatus.CANCELLED);      // сага замкнулась компенсацией
        verify(outboxEventFactory).orderStatusChanged(order, reason);     // событие построено с причиной
        verify(outboxRepository).save(any());                            // и положено в outbox (та же транзакция)
    }

    @Test
    void shouldBeIdempotentWhenSagaAlreadyCancelled() {
        SagaState saga = new SagaState();
        saga.setOrderId(ORDER_ID);
        saga.setState(SagaStatus.CANCELLED); // повторная доставка после компенсации (at-least-once)

        when(sagaStateRepository.findById(ORDER_ID)).thenReturn(Optional.of(saga));

        orderService.onInventoryReserved(ORDER_ID, false, "any reason");

        verify(orderRepository, never()).findById(any()); // заказ не трогаем
        verify(outboxRepository, never()).save(any());    // повторное событие не публикуем
        assertThat(saga.getState()).isEqualTo(SagaStatus.CANCELLED);
    }
}
