package ru.potekhincode.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.potekhincode.order.client.InventoryClient;
import ru.potekhincode.order.client.UnavailableItem;
import ru.potekhincode.order.dto.request.CreateOrderRequest;
import ru.potekhincode.order.dto.request.OrderItemRequest;
import ru.potekhincode.order.dto.response.OrderResponse;
import ru.potekhincode.order.exception.InsufficientStockException;
import ru.potekhincode.order.exception.InvalidStatusTransitionException;
import ru.potekhincode.order.exception.OrderNotFoundException;
import ru.potekhincode.order.mapper.OrderMapper;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OrderItem;
import ru.potekhincode.order.model.OrderStatus;
import ru.potekhincode.order.repository.OrderRepository;
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
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
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
}
