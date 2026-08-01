package ru.potekhincode.order.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.potekhincode.order.dto.request.CreateOrderRequest;
import ru.potekhincode.order.dto.response.OrderResponse;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.security.Caller;

import java.util.UUID;

public interface OrderService {

    OrderResponse create(CreateOrderRequest request, String userId);

    OrderResponse findById(UUID id, Caller caller);

    Page<OrderResponse> list(Pageable pageable, Caller caller);

    OrderResponse pay(UUID id, Caller caller);

    OrderResponse ship(UUID id, Caller caller);

    OrderResponse complete(UUID id, Caller caller);

    void onInventoryReserved(UUID orderId, boolean success, String reason);

    Order requireVisible(UUID id, Caller caller);
}
