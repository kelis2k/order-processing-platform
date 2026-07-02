package ru.potekhincode.auth.service;


import ru.potekhincode.auth.dto.*;

public interface AuthService {
    void register(RegisterRequest request);
    TokenResponse login(LoginRequest request);
    TokenResponse refresh(RefreshRequest request);
    void confirm(ConfirmRequest request);
}
