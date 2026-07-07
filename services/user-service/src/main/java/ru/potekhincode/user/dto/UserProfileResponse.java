package ru.potekhincode.user.dto;

import ru.potekhincode.user.model.Role;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String username,
        Role role,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
