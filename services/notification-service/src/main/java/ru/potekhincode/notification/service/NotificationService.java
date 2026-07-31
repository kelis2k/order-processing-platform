package ru.potekhincode.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import ru.potekhincode.notification.mail.EmailSender;
import ru.potekhincode.notification.mail.EmailTemplateRenderer;
import ru.potekhincode.notification.mail.RenderedEmail;
import ru.potekhincode.notification.model.DeliveryState;
import ru.potekhincode.notification.model.Notification;
import ru.potekhincode.notification.model.NotificationType;
import ru.potekhincode.notification.model.OrderStatuses;
import ru.potekhincode.notification.model.Recipient;
import ru.potekhincode.notification.rateLimit.EmailRateLimiter;
import ru.potekhincode.notification.repository.NotificationRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String CONFIRMATION_REQUESTED = "REQUESTED";

    private final NotificationRepository notificationRepository;
    private final RecipientService recipientService;
    private final EmailTemplateRenderer renderer;
    private final EmailSender emailSender;
    private final EmailRateLimiter rateLimiter;

    @Value("${app.public-url:http://localhost:8087}")
    private String publicUrl;

    public void onConfirmationRequested(String userId, String email, String username,
                                        String token, Instant expiresAt) {
        Notification notification = new Notification();
        notification.setType(NotificationType.USER_CONFIRMATION);
        notification.setAggregateId(userId);
        notification.setStatus(CONFIRMATION_REQUESTED);
        notification.setRecipientEmail(email);
        notification.setState(DeliveryState.PENDING);
        notification.setCreatedAt(Instant.now());

        try {
            notificationRepository.insert(notification);
        } catch (DuplicateKeyException e) {
            log.debug("Duplicate confirmation notification for user={}, skipping", userId);
            return;
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("username", username);
        variables.put("confirmUrl", publicUrl + "/auth/confirm?token=" + token);
        variables.put("expiresAt", expiresAt);

        deliver(notification, new Recipient(userId, email, username), variables);
    }

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

        Map<String, Object> variables = new HashMap<>();
        variables.put("orderId", notification.getAggregateId());
        variables.put("status", notification.getStatus());
        variables.put("reason", notification.getReason());
        variables.put("username", recipient.getUsername());

        deliver(notification, recipient, variables);
    }

    private void deliver(Notification notification, Recipient recipient,
                         Map<String, Object> variables) {
        if (!rateLimiter.tryAcquire(recipient.getUserId())) {
            notification.setState(DeliveryState.SUPPRESSED);
            log.info("Suppressed {} for order={} user={}: rate limit exceeded",
                    notification.getType(), notification.getAggregateId(), recipient.getUserId());
            notificationRepository.save(notification);
            return;
        }

        try {
            RenderedEmail email = renderer.render(notification.getType(), variables);
            emailSender.send(recipient.getEmail(), email.subject(), email.body());
            notification.setState(DeliveryState.SENT);
        } catch (Exception e) {
            notification.setState(DeliveryState.FAILED);
            notification.setError(e.getMessage());
            log.warn("Delivery FAILED for {} order={}: {}",
                    notification.getType(), notification.getAggregateId(), e.getMessage());
        }
        notificationRepository.save(notification);
    }


}
