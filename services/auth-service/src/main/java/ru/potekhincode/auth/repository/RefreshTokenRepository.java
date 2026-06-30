package ru.potekhincode.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.potekhincode.auth.model.RefreshToken;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
