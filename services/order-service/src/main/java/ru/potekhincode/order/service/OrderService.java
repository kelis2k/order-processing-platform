package ru.potekhincode.order.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.potekhincode.order.dto.request.CreateOrderRequest;
import ru.potekhincode.order.dto.response.OrderResponse;

import java.util.UUID;

public interface OrderService {

    OrderResponse create(CreateOrderRequest request);

    OrderResponse findById(UUID id);

    Page<OrderResponse> list(Pageable pageable);

    void onInventoryReserved(UUID orderId, boolean success);

}
