package ru.potekhincode.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import ru.potekhincode.notification.model.DeliveryState;
import ru.potekhincode.notification.model.Notification;
import ru.potekhincode.notification.model.NotificationType;
import ru.potekhincode.notification.model.OrderStatuses;
import ru.potekhincode.notification.model.Recipient;
import ru.potekhincode.notification.repository.NotificationRepository;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final RecipientService recipientService;

    public void onOrderCreated(String orderId, String userId) {
        record(NotificationType.ORDER_ACCEPTED, orderId, OrderStatuses.NEW, null, userId);
    }

    public void onStatusChanged(String orderId, String userId, String status, String reason) {
        record(NotificationType.ORDER_STATUS_CHANGED, orderId, status, reason, userId);
    }

    private void record(NotificationType type, String orderId, String status,
                        String reason, String userId) {

        Recipient recipient = recipientService.require(userId);   // нет получателя → исключение → retry

        Notification notification = new Notification();
        notification.setType(type);
        notification.setAggregateId(orderId);
        notification.setStatus(status);
        notification.setReason(reason);
        notification.setRecipientEmail(recipient.getEmail());
        notification.setState(DeliveryState.PENDING);
        notification.setCreatedAt(Instant.now());

        try {
            notificationRepository.insert(notification);
        } catch (DuplicateKeyException e) {
            // повторная доставка того же факта — письмо уже запланировано, второе не нужно
            log.debug("Duplicate notification {} for order={} status={}, skipping", type, orderId, status);
            return;
        }

        // 6.5: здесь будет реальная отправка через Spring Mail → MailHog
        log.info("EMAIL → {}: заказ {} — {}{}",
                recipient.getEmail(), orderId, status,
                reason == null ? "" : " (" + reason + ")");
    }
}