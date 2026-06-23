package ru.potekhincode.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.potekhincode.order.model.SagaState;

import java.util.UUID;

public interface SagaStateRepository extends JpaRepository<SagaState, UUID> {
}
