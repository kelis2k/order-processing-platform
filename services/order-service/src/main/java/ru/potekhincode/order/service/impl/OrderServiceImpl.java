package ru.potekhincode.order.service.impl;

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
import ru.potekhincode.order.model.*;
import ru.potekhincode.order.outbox.OutboxEventFactory;
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
    private final OutboxEventFactory outboxEventFactory;



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

        outboxRepository.save(outboxEventFactory.orderCreated(saved));

        return orderMapper.toResponse(saved);
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
    public void onInventoryReserved(UUID orderId, boolean success, String reason) {
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
            Order order = orderRepository.findById(orderId)
                            .orElseThrow(() -> new OrderNotFoundException(orderId));
            transition(order, OrderStatus.CANCELLED);
            saga.setState(SagaStatus.CANCELLED);
            outboxRepository.save(outboxEventFactory.orderStatusChanged(order, reason));
            log.info("Order {} cancelled (insufficient stock): {}", orderId, reason);
        }
    }

}
