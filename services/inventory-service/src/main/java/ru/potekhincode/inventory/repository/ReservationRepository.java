package ru.potekhincode.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.potekhincode.inventory.model.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    boolean existsByOrderId(String orderId);
}
