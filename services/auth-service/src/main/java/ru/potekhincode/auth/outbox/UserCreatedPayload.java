package ru.potekhincode.auth.outbox;

public record UserCreatedPayload(
        String userId,
        String email,
        String username,
        long timestamp
) {
}
