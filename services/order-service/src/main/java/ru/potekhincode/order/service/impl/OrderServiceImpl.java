package ru.potekhincode.order.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.potekhincode.order.client.InventoryClient;
import ru.potekhincode.order.client.UnavailableItem;
import ru.potekhincode.order.dto.request.CreateOrderRequest;
import ru.potekhincode.order.dto.response.OrderResponse;
import ru.potekhincode.order.exception.InsufficientStockException;
import ru.potekhincode.order.exception.InvalidStatusTransitionException;
import ru.potekhincode.order.exception.OrderNotFoundException;
import ru.potekhincode.order.mapper.OrderMapper;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OrderItem;
import ru.potekhincode.order.model.OrderStatus;
import ru.potekhincode.order.model.OutboxEvent;
import ru.potekhincode.order.model.SagaState;
import ru.potekhincode.order.model.SagaStatus;
import ru.potekhincode.order.outbox.OrderCreatedPayload;
import ru.potekhincode.order.repository.OrderRepository;
import ru.potekhincode.order.repository.OutboxRepository;
import ru.potekhincode.order.repository.SagaStateRepository;
import ru.potekhincode.order.service.OrderService;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final InventoryClient inventoryClient;
    private final OutboxRepository outboxRepository;
    private final SagaStateRepository sagaStateRepository;
    private final ObjectMapper objectMapper;


    @Override
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        List<UnavailableItem> unavailable = inventoryClient.checkAvailability(request.items());
        if (!unavailable.isEmpty()) {
            throw new InsufficientStockException(unavailable);
        }

        Order order = new Order();
        order.setUserId(request.userId());
        order.setStatus(OrderStatus.NEW);

        request.items().forEach(
                itemRequest -> {
                    OrderItem item = orderMapper.toEntity(itemRequest);
                    order.addItem(item);
                }
        );

        Order saved = orderRepository.save(order);

        SagaState saga = new SagaState();
        saga.setOrderId(saved.getId());
        saga.setState(SagaStatus.AWAITING_RESERVATION);
        sagaStateRepository.save(saga);

        outboxRepository.save(buildOrderCreatedOutbox(saved));

        return orderMapper.toResponse(saved);
    }

    private OutboxEvent buildOrderCreatedOutbox(Order order) {
        List<OrderCreatedPayload.Item> items = order.getItems().stream()
                .map(i -> new OrderCreatedPayload.Item(i.getProductId(), i.getQuantity()))
                .toList();
        OrderCreatedPayload payload = new OrderCreatedPayload(
                order.getId().toString(),
                order.getUserId(),
                items,
                System.currentTimeMillis()
        );

        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("ORDER");
        event.setAggregateId(order.getId().toString());
        event.setEventType("OrderCreated");
        event.setTopic("order.created");
        event.setMsgKey(order.getId().toString());
        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize OrderCreated payload", e);
        }
        return event;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse findById(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        return orderMapper.toResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> list(Pageable pageable) {
        return orderRepository.findAll(pageable)
                .map(orderMapper::toResponse);
    }

    @Transactional
    public void transition(Order order, OrderStatus target) {
        if (!order.getStatus().canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(order.getStatus(), target);
        }
        order.setStatus(target);
    }

    @Override
    @Transactional
    public void onInventoryReserved(UUID orderId, boolean success) {
        SagaState saga = sagaStateRepository.findById(orderId).orElse(null);
        if (saga == null) {
            log.warn("No saga state for orderId={}, ignoring inventory.reserved", orderId);
            return;
        }
        // идемпотентность: at-least-once из outbox — реагируем только из ожидания резерва
        if (saga.getState() != SagaStatus.AWAITING_RESERVATION) {
            log.debug("Saga orderId={} already in state {}, skipping", orderId, saga.getState());
            return;
        }

        if (success) {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException(orderId));
            transition(order, OrderStatus.RESERVED);   // FSM-гард NEW→RESERVED
            saga.setState(SagaStatus.RESERVED);
            log.info("Order {} reserved", orderId);
        } else {
            // ветка компенсации — шаг 4.5 (CANCELLED + order.status-changed)
            log.warn("Reservation failed for orderId={}, compensation deferred to 4.5", orderId);
        }
    }

}
