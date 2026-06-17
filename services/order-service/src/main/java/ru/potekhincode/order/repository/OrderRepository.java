package ru.potekhincode.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.potekhincode.order.model.Order;

import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
}
