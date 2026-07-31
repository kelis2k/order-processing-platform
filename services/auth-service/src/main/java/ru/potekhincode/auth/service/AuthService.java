package ru.potekhincode.auth.service;


import ru.potekhincode.auth.dto.ConfirmRequest;
import ru.potekhincode.auth.dto.LoginRequest;
import ru.potekhincode.auth.dto.RefreshRequest;
import ru.potekhincode.auth.dto.RegisterRequest;
import ru.potekhincode.auth.dto.TokenResponse;
import ru.potekhincode.auth.model.AuthProvider;
import ru.potekhincode.auth.model.Role;

import java.util.UUID;

public interface AuthService {
    void register(RegisterRequest request);
    TokenResponse login(LoginRequest request);
    TokenResponse refresh(RefreshRequest request);
    void confirm(ConfirmRequest request);
    TokenResponse oauthLogin(String email, String username, AuthProvider provider, String providerId);
    void applyRoleChange(UUID userId, Role role);
}
