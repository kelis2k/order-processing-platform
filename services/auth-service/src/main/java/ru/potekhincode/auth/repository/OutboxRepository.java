package ru.potekhincode.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.potekhincode.auth.model.OutboxEvent;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
