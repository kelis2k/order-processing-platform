package ru.potekhincode.order.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.potekhincode.order.dto.request.CreateOrderRequest;
import ru.potekhincode.order.dto.response.OrderResponse;
import ru.potekhincode.order.security.Caller;
import ru.potekhincode.order.service.OrderService;

import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request,
                                @AuthenticationPrincipal Jwt jwt
    ) {
        return orderService.create(request, jwt.getSubject());
    }

    @GetMapping("/{id}")
    public OrderResponse findById(@PathVariable UUID id,
                                  @AuthenticationPrincipal Jwt jwt
    ) {
        return orderService.findById(id, Caller.from(jwt));
    }

    @GetMapping
    public Page<OrderResponse> list(@PageableDefault(size = 20, sort = "createdAt") Pageable pageable,
                                    @AuthenticationPrincipal Jwt jwt) {
        return orderService.list(pageable, Caller.from(jwt));
    }
}
