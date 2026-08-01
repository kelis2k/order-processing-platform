package ru.potekhincode.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.potekhincode.inventory.model.ReservationLine;

import java.util.List;

public interface ReservationLineRepository extends JpaRepository<ReservationLine, Long> {

    List<ReservationLine> findByOrderId(String orderId);
}
