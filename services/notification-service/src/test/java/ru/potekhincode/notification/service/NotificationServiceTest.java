package ru.potekhincode.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import ru.potekhincode.notification.exception.RecipientNotFoundException;
import ru.potekhincode.notification.model.DeliveryState;
import ru.potekhincode.notification.model.Notification;
import ru.potekhincode.notification.model.NotificationType;
import ru.potekhincode.notification.model.Recipient;
import ru.potekhincode.notification.repository.NotificationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String ORDER_ID = "0f1e2d3c-0000-0000-0000-000000000001";
    private static final String USER_ID = "3f0e2a1c-0000-0000-0000-000000000001";
    private static final String EMAIL = "notify@example.com";

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private RecipientService recipientService;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    void orderCreatedShouldRecordAcceptedNotificationForOwner() {
        when(recipientService.require(USER_ID)).thenReturn(new Recipient(USER_ID, EMAIL, "notify"));

        notificationService.onOrderCreated(ORDER_ID, USER_ID);

        Notification saved = captureInserted();
        assertThat(saved.getType()).isEqualTo(NotificationType.ORDER_ACCEPTED);
        assertThat(saved.getAggregateId()).isEqualTo(ORDER_ID);
        assertThat(saved.getStatus()).isEqualTo("NEW");
        assertThat(saved.getRecipientEmail()).isEqualTo(EMAIL);
        assertThat(saved.getState()).isEqualTo(DeliveryState.PENDING);
    }

    /** Причина отмены доезжает от inventory через SAGA до письма. */
    @Test
    void statusChangedShouldRecordNotificationWithReason() {
        when(recipientService.require(USER_ID)).thenReturn(new Recipient(USER_ID, EMAIL, "notify"));
        String reason = "Insufficient stock for product p-1: requested 1, available 0";

        notificationService.onStatusChanged(ORDER_ID, USER_ID, "CANCELLED", reason);

        Notification saved = captureInserted();
        assertThat(saved.getType()).isEqualTo(NotificationType.ORDER_STATUS_CHANGED);
        assertThat(saved.getStatus()).isEqualTo("CANCELLED");
        assertThat(saved.getReason()).isEqualTo(reason);
    }

    /**
     * Kafka даёт at-least-once. Повтор того же факта отбивается уникальным индексом
     * (type, aggregateId, status) на уровне БД — исключение глотаем, второго письма нет.
     */
    @Test
    void duplicateDeliveryShouldBeSwallowedAndNotResentToUpstream() {
        when(recipientService.require(USER_ID)).thenReturn(new Recipient(USER_ID, EMAIL, "notify"));
        doThrow(new DuplicateKeyException("dedup_type_aggregate_status"))
                .when(notificationRepository).insert(any(Notification.class));

        assertThatCode(() -> notificationService.onStatusChanged(ORDER_ID, USER_ID, "RESERVED", null))
                .doesNotThrowAnyException();   // повтор — не ошибка, а no-op
    }

    /**
     * Получатель ещё неизвестен (user.created не доехал — топики читаются параллельно):
     * исключение уходит наружу → DefaultErrorHandler перечитает сообщение с backoff.
     * Письмо не теряется и не создаётся «без адреса».
     */
    @Test
    void unknownRecipientShouldPropagateSoKafkaRetries() {
        when(recipientService.require(USER_ID)).thenThrow(new RecipientNotFoundException(USER_ID));

        assertThatThrownBy(() -> notificationService.onOrderCreated(ORDER_ID, USER_ID))
                .isInstanceOf(RecipientNotFoundException.class);

        verify(notificationRepository, never()).insert(any(Notification.class));
    }

    private Notification captureInserted() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).insert(captor.capture());
        return captor.getValue();
    }
}
