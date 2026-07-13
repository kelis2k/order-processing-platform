package ru.potekhincode.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.potekhincode.order.dto.response.OrderResponse;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OrderStatus;
import ru.potekhincode.order.repository.OrderRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT правил владения заказом на REST-поверхности (шаг 6.3, ADR 0008).
 * <p>
 * До 6.3 {@code GET /orders/{id}} и {@code GET /orders} были доступны любому
 * аутентифицированному пользователю: чужой заказ читался по id, а список отдавал все заказы
 * платформы. Теперь правило одно на оба транспорта (REST и gRPC) и живёт в
 * {@code OrderService.requireVisible}: заказ виден владельцу или ADMIN.
 */
class OrderAccessIT extends AbstractIntegrationTest {

    private static final String OWNER_ID = "owner-it-1";
    private static final String STRANGER_ID = "stranger-it-2";
    private static final String ADMIN_ID = "admin-it-3";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OrderRepository orderRepository;

    private UUID ownOrderId;

    @BeforeEach
    void seed() {
        orderRepository.deleteAll();
        ownOrderId = save(OWNER_ID).getId();
        save(STRANGER_ID);
    }

    @Test
    void ownerCanReadOwnOrder() {
        ResponseEntity<OrderResponse> response = get("/orders/" + ownOrderId, OWNER_ID, "ROLE_USER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().userId()).isEqualTo(OWNER_ID);
    }

    @Test
    void strangerCannotReadForeignOrder() {
        // тело ошибки — application/problem+json, поэтому читаем как строку, а не в OrderResponse
        ResponseEntity<String> response = getRaw("/orders/" + ownOrderId, STRANGER_ID, "ROLE_USER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanReadForeignOrder() {
        ResponseEntity<OrderResponse> response = get("/orders/" + ownOrderId, ADMIN_ID, "ROLE_ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownOrderIsNotFound() {
        ResponseEntity<String> response = getRaw("/orders/" + UUID.randomUUID(), OWNER_ID, "ROLE_USER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listReturnsOnlyOwnOrdersForRegularUser() {
        ResponseEntity<Map<String, Object>> response = list(OWNER_ID, "ROLE_USER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userIdsIn(response)).containsExactly(OWNER_ID);
    }

    @Test
    void listReturnsAllOrdersForAdmin() {
        ResponseEntity<Map<String, Object>> response = list(ADMIN_ID, "ROLE_ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userIdsIn(response)).containsExactlyInAnyOrder(OWNER_ID, STRANGER_ID);
    }

    // --- helpers ---

    private Order save(String userId) {
        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.NEW);
        order.setTotalAmount(BigDecimal.ZERO);
        return orderRepository.save(order);
    }

    private ResponseEntity<OrderResponse> get(String path, String subject, String role) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearer(subject, role)), OrderResponse.class);
    }

    private ResponseEntity<String> getRaw(String path, String subject, String role) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearer(subject, role)), String.class);
    }

    private ResponseEntity<Map<String, Object>> list(String subject, String role) {
        return rest.exchange("/orders", HttpMethod.GET,
                new HttpEntity<>(bearer(subject, role)),
                new ParameterizedTypeReference<>() {
                });
    }

    @SuppressWarnings("unchecked")
    private List<String> userIdsIn(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
        return content.stream().map(order -> (String) order.get("userId")).toList();
    }
}
