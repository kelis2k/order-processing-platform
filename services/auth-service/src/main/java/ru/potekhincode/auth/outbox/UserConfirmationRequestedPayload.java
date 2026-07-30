package ru.potekhincode.auth.outbox;

public record UserConfirmationRequestedPayload(
        String userId,
        String email,
        String username,
        String token,
        long expiresAt,
        long timestamp
) {
}
