package ru.potekhincode.user.dto;

import jakarta.validation.constraints.NotNull;
import ru.potekhincode.user.model.Role;

public record UpdateRoleRequest(
        @NotNull Role role
) {
}
