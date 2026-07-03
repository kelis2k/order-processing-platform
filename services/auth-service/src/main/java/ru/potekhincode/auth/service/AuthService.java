package ru.potekhincode.auth.service;


import ru.potekhincode.auth.dto.*;
import ru.potekhincode.auth.model.AuthProvider;

public interface AuthService {
    void register(RegisterRequest request);
    TokenResponse login(LoginRequest request);
    TokenResponse refresh(RefreshRequest request);
    void confirm(ConfirmRequest request);
    TokenResponse oauthLogin(String email, String username, AuthProvider provider, String providerId);
}
