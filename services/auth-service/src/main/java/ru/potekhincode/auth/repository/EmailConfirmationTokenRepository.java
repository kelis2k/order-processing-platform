package ru.potekhincode.auth.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import ru.potekhincode.auth.model.EmailConfirmationToken;

import java.util.Optional;
import java.util.UUID;

public interface EmailConfirmationTokenRepository extends JpaRepository<EmailConfirmationToken, UUID> {

    Optional<EmailConfirmationToken> findByTokenHash(String tokenHash);
}
