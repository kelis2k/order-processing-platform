package ru.potekhincode.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mail.MailSendException;
import ru.potekhincode.notification.exception.RecipientNotFoundException;
import ru.potekhincode.notification.mail.EmailSender;
import ru.potekhincode.notification.mail.EmailTemplateRenderer;
import ru.potekhincode.notification.mail.RenderedEmail;
import ru.potekhincode.notification.rateLimit.EmailRateLimiter;
import ru.potekhincode.notification.model.DeliveryState;
import ru.potekhincode.notification.model.Notification;
import ru.potekhincode.notification.model.NotificationType;
import ru.potekhincode.notification.model.Recipient;
import ru.potekhincode.notification.repository.NotificationRepository;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String ORDER_ID = "0f1e2d3c-0000-0000-0000-000000000001";
    private static final String USER_ID = "3f0e2a1c-0000-0000-0000-000000000001";
    private static final String EMAIL = "notify@example.com";
    private static final RenderedEmail RENDERED = new RenderedEmail("subj", "<p>body</p>");

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private RecipientService recipientService;
    @Mock
    private EmailTemplateRenderer renderer;
    @Mock
    private EmailSender emailSender;
    @Mock
    private EmailRateLimiter rateLimiter;

    @InjectMocks
    private NotificationService notificationService;

    /** Общий happy-путь: получатель есть, лимит не превышен, шаблон рендерится. */
    private void stubRecipientAndRender() {
        when(recipientService.require(USER_ID)).thenReturn(new Recipient(USER_ID, EMAIL, "notify"));
        when(rateLimiter.tryAcquire(USER_ID)).thenReturn(true);
        when(renderer.render(any(NotificationType.class), anyMap())).thenReturn(RENDERED);
    }

    @Test
    void orderCreatedShouldInsertAcceptedNotificationAndSendEmail() {
        stubRecipientAndRender();

        notificationService.onOrderCreated(ORDER_ID, USER_ID);

        Notification inserted = captureInserted();
        assertThat(inserted.getType()).isEqualTo(NotificationType.ORDER_ACCEPTED);
        assertThat(inserted.getAggregateId()).isEqualTo(ORDER_ID);
        assertThat(inserted.getStatus()).isEqualTo("NEW");
        assertThat(inserted.getRecipientEmail()).isEqualTo(EMAIL);

        verify(emailSender).send(eq(EMAIL), eq("subj"), eq("<p>body</p>"));
        assertThat(captureSaved().getState()).isEqualTo(DeliveryState.SENT);
    }

    /** Причина отмены доезжает от inventory через SAGA до документа и письма. */
    @Test
    void statusChangedShouldCarryReasonAndSend() {
        stubRecipientAndRender();
        String reason = "Insufficient stock for product p-1: requested 1, available 0";

        notificationService.onStatusChanged(ORDER_ID, USER_ID, "CANCELLED", reason);

        Notification inserted = captureInserted();
        assertThat(inserted.getType()).isEqualTo(NotificationType.ORDER_STATUS_CHANGED);
        assertThat(inserted.getStatus()).isEqualTo("CANCELLED");
        assertThat(inserted.getReason()).isEqualTo(reason);
        assertThat(captureSaved().getState()).isEqualTo(DeliveryState.SENT);
    }

    /** Сбой SMTP → документ FAILED + причина; исключение НЕ пробрасывается (ретраев нет). */
    @Test
    void deliveryFailureShouldMarkFailedWithoutRethrow() {
        stubRecipientAndRender();
        doThrow(new MailSendException("smtp down")).when(emailSender).send(any(), any(), any());

        assertThatCode(() -> notificationService.onOrderCreated(ORDER_ID, USER_ID))
                .doesNotThrowAnyException();

        Notification saved = captureSaved();
        assertThat(saved.getState()).isEqualTo(DeliveryState.FAILED);
        assertThat(saved.getError()).contains("smtp down");
    }

    /**
     * Kafka даёт at-least-once. Повтор того же факта отбивается уникальным индексом
     * (type, aggregateId, status) — исключение глотаем, письмо не шлём повторно.
     */
    @Test
    void duplicateDeliveryShouldBeSwallowedAndNotSend() {
        when(recipientService.require(USER_ID)).thenReturn(new Recipient(USER_ID, EMAIL, "notify"));
        doThrow(new DuplicateKeyException("dedup_type_aggregate_status"))
                .when(notificationRepository).insert(any(Notification.class));

        assertThatCode(() -> notificationService.onStatusChanged(ORDER_ID, USER_ID, "RESERVED", null))
                .doesNotThrowAnyException();

        verify(emailSender, never()).send(any(), any(), any());
        verify(notificationRepository, never()).save(any());   // статус не трогаем — уже обработано
    }

    /**
     * Получатель ещё неизвестен (user.created не доехал): исключение уходит наружу →
     * DefaultErrorHandler перечитает сообщение с backoff. Документ не создаётся.
     */
    @Test
    void unknownRecipientShouldPropagateSoKafkaRetries() {
        when(recipientService.require(USER_ID)).thenThrow(new RecipientNotFoundException(USER_ID));

        assertThatThrownBy(() -> notificationService.onOrderCreated(ORDER_ID, USER_ID))
                .isInstanceOf(RecipientNotFoundException.class);

        verify(notificationRepository, never()).insert(any(Notification.class));
        verify(emailSender, never()).send(any(), any(), any());
    }

    /** Лимит анти-спама превышен → SUPPRESSED, письмо не рендерим и не шлём (6.6). */
    @Test
    void rateLimitedShouldSuppressWithoutSending() {
        when(recipientService.require(USER_ID)).thenReturn(new Recipient(USER_ID, EMAIL, "notify"));
        when(rateLimiter.tryAcquire(USER_ID)).thenReturn(false);

        notificationService.onOrderCreated(ORDER_ID, USER_ID);

        assertThat(captureSaved().getState()).isEqualTo(DeliveryState.SUPPRESSED);
        verify(renderer, never()).render(any(), anyMap());
        verify(emailSender, never()).send(any(), any(), any());
    }

    private Notification captureInserted() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).insert(captor.capture());
        return captor.getValue();
    }

    private Notification captureSaved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        return captor.getValue();
    }
}
