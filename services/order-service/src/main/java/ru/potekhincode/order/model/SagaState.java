package ru.potekhincode.order.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "saga_state")
@Getter
@Setter
@NoArgsConstructor
public class SagaState {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SagaStatus state;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
