package ru.potekhincode.auth.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.potekhincode.auth.config.JwtProperties;
import ru.potekhincode.auth.dto.LoginRequest;
import ru.potekhincode.auth.dto.RefreshRequest;
import ru.potekhincode.auth.dto.RegisterRequest;
import ru.potekhincode.auth.dto.TokenResponse;
import ru.potekhincode.auth.exception.EmailAlreadyUsedException;
import ru.potekhincode.auth.exception.InvalidCredentialsException;
import ru.potekhincode.auth.exception.InvalidRefreshTokenException;
import ru.potekhincode.auth.model.RefreshToken;
import ru.potekhincode.auth.model.Role;
import ru.potekhincode.auth.model.User;
import ru.potekhincode.auth.repository.RefreshTokenRepository;
import ru.potekhincode.auth.repository.UserRepository;
import ru.potekhincode.auth.security.JwtService;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты сервисного слоя auth-service на Mockito: все коллабораторы (репозитории,
 * энкодер, JWT-сервис) замоканы, проверяется только бизнес-логика регистрации/входа/ротации.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String EMAIL = "alice@example.com";
    private static final String RAW_PASSWORD = "password1";
    private static final String ENCODED_PASSWORD = "{bcrypt}encoded";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private final JwtProperties jwtProperties = new JwtProperties(
            "auth-service", Duration.ofMinutes(15), Duration.ofDays(7), null, null);

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(userRepository, refreshTokenRepository,
                passwordEncoder, jwtService, jwtProperties);
    }

    @Test
    void registerShouldEncodePasswordAndSaveUserWithRoleUser() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);

        service.register(new RegisterRequest(EMAIL, "alice", RAW_PASSWORD));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getPasswordHash()).isEqualTo(ENCODED_PASSWORD);
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void registerShouldThrowAndNotSaveWhenEmailExists() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest(EMAIL, "alice", RAW_PASSWORD)))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void loginShouldReturnTokensAndPersistRefreshToken() {
        User user = sampleUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("ACCESS");

        TokenResponse resp = service.login(new LoginRequest(EMAIL, RAW_PASSWORD));

        assertThat(resp.accessToken()).isEqualTo("ACCESS");
        assertThat(resp.refreshToken()).isNotBlank();
        assertThat(resp.tokenType()).isEqualTo("Bearer");
        assertThat(resp.expiresIn()).isEqualTo(Duration.ofMinutes(15).toSeconds());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void loginShouldThrowOnWrongPassword() {
        User user = sampleUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", ENCODED_PASSWORD)).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void loginShouldThrowOnUnknownEmail() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("ghost@example.com", RAW_PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshShouldRevokeOldTokenAndIssueNew() {
        User user = sampleUser();
        RefreshToken stored = activeToken(user.getId());
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("ACCESS2");

        TokenResponse resp = service.refresh(new RefreshRequest("old-token"));

        assertThat(stored.isRevoked()).isTrue();
        assertThat(resp.accessToken()).isEqualTo("ACCESS2");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refreshShouldThrowWhenTokenRevoked() {
        RefreshToken stored = activeToken(UUID.randomUUID());
        stored.setRevoked(true);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.refresh(new RefreshRequest("t")))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void refreshShouldThrowWhenTokenExpired() {
        RefreshToken stored = activeToken(UUID.randomUUID());
        stored.setExpiresAt(OffsetDateTime.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.refresh(new RefreshRequest("t")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refreshShouldThrowWhenTokenUnknown() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(new RefreshRequest("t")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    private User sampleUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(EMAIL);
        user.setUsername("alice");
        user.setPasswordHash(ENCODED_PASSWORD);
        user.setRole(Role.ROLE_USER);
        user.setEnabled(true);
        return user;
    }

    private RefreshToken activeToken(UUID userId) {
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setExpiresAt(OffsetDateTime.now().plusDays(7));
        token.setRevoked(false);
        return token;
    }
}
