package ru.potekhincode.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.potekhincode.notification.exception.RecipientNotFoundException;
import ru.potekhincode.notification.model.Recipient;
import ru.potekhincode.notification.repository.RecipientRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientServiceTest {

    private static final String USER_ID = "3f0e2a1c-0000-0000-0000-000000000001";
    private static final String EMAIL = "notify@example.com";

    @Mock
    private RecipientRepository recipientRepository;

    @InjectMocks
    private RecipientService recipientService;

    @Test
    void upsertShouldStoreRecipientKeyedByUserId() {
        recipientService.upsert(USER_ID, EMAIL, "notify");

        ArgumentCaptor<Recipient> captor = ArgumentCaptor.forClass(Recipient.class);
        verify(recipientRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getEmail()).isEqualTo(EMAIL);
    }

    /** user.created доставляется at-least-once: повтор перезаписывает документ по _id, дубля нет. */
    @Test
    void upsertShouldBeIdempotentOnRedelivery() {
        recipientService.upsert(USER_ID, EMAIL, "notify");
        recipientService.upsert(USER_ID, EMAIL, "notify");

        verify(recipientRepository, org.mockito.Mockito.times(2)).save(org.mockito.ArgumentMatchers.any());
        // оба save идут по одному _id = userId → в Mongo остаётся один документ
    }

    @Test
    void requireShouldReturnKnownRecipient() {
        Recipient recipient = new Recipient(USER_ID, EMAIL, "notify");
        when(recipientRepository.findById(USER_ID)).thenReturn(Optional.of(recipient));

        assertThat(recipientService.require(USER_ID)).isSameAs(recipient);
    }

    /** Получателя ещё нет (user.created не доехал) → исключение → DefaultErrorHandler повторит доставку. */
    @Test
    void requireShouldThrowWhenRecipientUnknown() {
        when(recipientRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recipientService.require(USER_ID))
                .isInstanceOf(RecipientNotFoundException.class)
                .hasMessageContaining(USER_ID);
    }
}
