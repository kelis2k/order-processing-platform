package ru.potekhincode.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.potekhincode.notification.exception.RecipientNotFoundException;
import ru.potekhincode.notification.model.Recipient;
import ru.potekhincode.notification.repository.RecipientRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecipientService {

    private final RecipientRepository recipientRepository;

    /** Повторная доставка user.created (at-least-once) безвредна: _id = userId, перезапись тем же. */
    public void upsert(String userId, String email, String username) {
        recipientRepository.save(new Recipient(userId, email, username));
        log.info("Recipient upserted: userId={}, email={}", userId, email);
    }

    public Recipient require(String userId) {
        return recipientRepository.findById(userId)
                .orElseThrow(() -> new RecipientNotFoundException(userId));
    }
}
