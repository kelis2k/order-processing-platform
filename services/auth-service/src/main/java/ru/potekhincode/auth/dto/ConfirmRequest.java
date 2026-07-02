package ru.potekhincode.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmRequest(
        @NotBlank String token) { }
