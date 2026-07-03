package ru.potekhincode.auth.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.potekhincode.auth.config.JwtProperties;
import ru.potekhincode.auth.dto.ConfirmRequest;
import ru.potekhincode.auth.dto.LoginRequest;
import ru.potekhincode.auth.dto.RefreshRequest;
import ru.potekhincode.auth.dto.RegisterRequest;
import ru.potekhincode.auth.dto.TokenResponse;
import ru.potekhincode.auth.exception.EmailAlreadyUsedException;
import ru.potekhincode.auth.exception.EmailNotConfirmedException;
import ru.potekhincode.auth.exception.InvalidConfirmationTokenException;
import ru.potekhincode.auth.exception.InvalidCredentialsException;
import ru.potekhincode.auth.exception.InvalidRefreshTokenException;
import ru.potekhincode.auth.model.AuthProvider;
import ru.potekhincode.auth.model.EmailConfirmationToken;
import ru.potekhincode.auth.model.OutboxEvent;
import ru.potekhincode.auth.model.RefreshToken;
import ru.potekhincode.auth.model.Role;
import ru.potekhincode.auth.model.User;
import ru.potekhincode.auth.outbox.OutboxEventFactory;
import ru.potekhincode.auth.repository.EmailConfirmationTokenRepository;
import ru.potekhincode.auth.repository.OutboxRepository;
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
 * энкодер, JWT-сервис, фабрика/репозиторий Outbox) замоканы, проверяется только бизнес-логика
 * регистрации/подтверждения/входа/ротации.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String EMAIL = "alice@example.com";
    private static final String RAW_PASSWORD = "password1";
    private static final String ENCODED_PASSWORD = "{bcrypt}encoded";
    private static final String OAUTH_EMAIL = "bob@example.com";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private OutboxEventFactory outboxEventFactory;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private EmailConfirmationTokenRepository confirmationTokenRepository;

    private final JwtProperties jwtProperties = new JwtProperties(
            "auth-service", Duration.ofMinutes(15), Duration.ofDays(7), null, null);
    private final Duration confirmationTtl = Duration.ofHours(24);

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(userRepository, refreshTokenRepository,
                passwordEncoder, jwtService, jwtProperties,
                outboxEventFactory, outboxRepository, confirmationTokenRepository, confirmationTtl);
    }

    @Test
    void registerShouldEncodePasswordAndSaveDisabledUserWithRoleUser() {
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
        assertThat(saved.isEnabled()).isFalse();
    }

    @Test
    void registerShouldWriteUserCreatedToOutboxAndConfirmationToken() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        OutboxEvent event = new OutboxEvent();
        when(outboxEventFactory.userCreated(any(User.class))).thenReturn(event);

        service.register(new RegisterRequest(EMAIL, "alice", RAW_PASSWORD));

        verify(outboxEventFactory).userCreated(any(User.class));
        verify(outboxRepository).save(event);
        verify(confirmationTokenRepository).save(any(EmailConfirmationToken.class));
    }

    @Test
    void registerShouldThrowAndNotSaveWhenEmailExists() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest(EMAIL, "alice", RAW_PASSWORD)))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
        verify(outboxRepository, never()).save(any());
        verify(confirmationTokenRepository, never()).save(any());
    }

    @Test
    void confirmShouldEnableUserAndMarkTokenUsed() {
        User user = disabledUser();
        EmailConfirmationToken token = activeConfirmationToken(user.getId());
        when(confirmationTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.confirm(new ConfirmRequest("raw-token"));

        assertThat(user.isEnabled()).isTrue();
        assertThat(token.isUsed()).isTrue();
    }

    @Test
    void confirmShouldThrowWhenTokenUnknown() {
        when(confirmationTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(new ConfirmRequest("raw-token")))
                .isInstanceOf(InvalidConfirmationTokenException.class);

        verify(userRepository, never()).findById(any());
    }

    @Test
    void confirmShouldThrowWhenTokenAlreadyUsed() {
        EmailConfirmationToken token = activeConfirmationToken(UUID.randomUUID());
        token.setUsed(true);
        when(confirmationTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.confirm(new ConfirmRequest("raw-token")))
                .isInstanceOf(InvalidConfirmationTokenException.class);

        verify(userRepository, never()).findById(any());
    }

    @Test
    void confirmShouldThrowWhenTokenExpired() {
        EmailConfirmationToken token = activeConfirmationToken(UUID.randomUUID());
        token.setExpiresAt(OffsetDateTime.now().minusSeconds(1));
        when(confirmationTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.confirm(new ConfirmRequest("raw-token")))
                .isInstanceOf(InvalidConfirmationTokenException.class);

        verify(userRepository, never()).findById(any());
    }

    @Test
    void loginShouldReturnTokensAndPersistRefreshToken() {
        User user = enabledUser();
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
    void loginShouldThrowWhenEmailNotConfirmed() {
        User user = disabledUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                .isInstanceOf(EmailNotConfirmedException.class);

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void loginShouldThrowOnWrongPassword() {
        User user = enabledUser();
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
        User user = enabledUser();
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

    @Test
    void oauthLoginShouldCreateEnabledUserAndPublishUserCreatedWhenEmailUnknown() {
        when(userRepository.findByEmail(OAUTH_EMAIL)).thenReturn(Optional.empty());
        OutboxEvent event = new OutboxEvent();
        when(outboxEventFactory.userCreated(any(User.class))).thenReturn(event);
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("ACCESS");

        TokenResponse resp = service.oauthLogin(OAUTH_EMAIL, "bob", AuthProvider.GOOGLE, "google-sub-1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo(OAUTH_EMAIL);
        assertThat(saved.getUsername()).isEqualTo("bob");
        assertThat(saved.getPasswordHash()).isNull();          // внешний вход — пароля нет
        assertThat(saved.isEnabled()).isTrue();                 // провайдер верифицировал email
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(saved.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(saved.getProviderId()).isEqualTo("google-sub-1");

        verify(outboxRepository).save(event);                   // user.created опубликован
        assertThat(resp.accessToken()).isEqualTo("ACCESS");
        assertThat(resp.refreshToken()).isNotBlank();
    }

    @Test
    void oauthLoginShouldReuseExistingUserWithoutDuplicateEventWhenEmailKnown() {
        User existing = enabledUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        when(jwtService.generateAccessToken(existing)).thenReturn("ACCESS");

        TokenResponse resp = service.oauthLogin(EMAIL, "ignored", AuthProvider.GITHUB, "gh-42");

        verify(userRepository, never()).save(any());            // линковка: без дубля юзера
        verify(outboxEventFactory, never()).userCreated(any()); // и без повторного user.created
        verify(outboxRepository, never()).save(any());
        verify(refreshTokenRepository).save(any(RefreshToken.class)); // но токены всё равно выдаём
        assertThat(resp.accessToken()).isEqualTo("ACCESS");
    }

    private User enabledUser() {
        User user = sampleUser();
        user.setEnabled(true);
        return user;
    }

    private User disabledUser() {
        User user = sampleUser();
        user.setEnabled(false);
        return user;
    }

    private User sampleUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(EMAIL);
        user.setUsername("alice");
        user.setPasswordHash(ENCODED_PASSWORD);
        user.setRole(Role.ROLE_USER);
        return user;
    }

    private EmailConfirmationToken activeConfirmationToken(UUID userId) {
        EmailConfirmationToken token = new EmailConfirmationToken();
        token.setUserId(userId);
        token.setExpiresAt(OffsetDateTime.now().plusHours(1));
        token.setUsed(false);
        return token;
    }

    private RefreshToken activeToken(UUID userId) {
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setExpiresAt(OffsetDateTime.now().plusDays(7));
        token.setRevoked(false);
        return token;
    }
}
