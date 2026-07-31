package ru.potekhincode.notification.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("notifications")
@CompoundIndex(
        name = "dedup_type_aggregate_status",
        def = "{'type': 1, 'aggregateId': 1, 'status': 1}",
        unique = true)                                   // ← гарантия «одно письмо на факт»
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    private String id;

    private NotificationType type;
    private String aggregateId;      // orderId
    private String status;           // NEW / RESERVED / CANCELLED
    private String reason;           // причина отмены, может быть null
    private String recipientEmail;
    private DeliveryState state;
    private String error;
    private Instant createdAt;
}
