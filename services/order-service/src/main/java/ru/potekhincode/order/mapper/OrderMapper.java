package ru.potekhincode.order.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import ru.potekhincode.order.dto.request.OrderItemRequest;
import ru.potekhincode.order.dto.response.OrderItemResponse;
import ru.potekhincode.order.dto.response.OrderResponse;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OrderItem;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    OrderResponse toResponse(Order order);

    OrderItemResponse toItemResponse(OrderItem item);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "order", ignore = true)
    @Mapping(target = "unitPrice", ignore = true)
    OrderItem toEntity(OrderItemRequest request);

}
