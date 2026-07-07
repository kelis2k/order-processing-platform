package ru.potekhincode.user.outbox;

public record UserRoleChangedPayload(
        String userId,
        String role,
        long timestamp
) {
}
