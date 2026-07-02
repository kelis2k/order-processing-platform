package ru.potekhincode.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.potekhincode.auth.dto.ConfirmRequest;
import ru.potekhincode.auth.dto.LoginRequest;
import ru.potekhincode.auth.dto.RefreshRequest;
import ru.potekhincode.auth.dto.RegisterRequest;
import ru.potekhincode.auth.dto.TokenResponse;
import ru.potekhincode.auth.model.EmailConfirmationToken;
import ru.potekhincode.auth.model.User;
import ru.potekhincode.auth.repository.EmailConfirmationTokenRepository;
import ru.potekhincode.auth.repository.OutboxRepository;
import ru.potekhincode.auth.repository.RefreshTokenRepository;
import ru.potekhincode.auth.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной IT auth-service на полном Spring-контексте + Testcontainers PostgreSQL + Kafka.
 * REST — настоящая точка входа: {@code /auth/register|login|refresh|confirm} через HTTP.
 * <p>
 * После шага 5.2 регистрация создаёт <b>неподтверждённого</b> пользователя ({@code enabled=false}),
 * поэтому happy-path входа проходит через подтверждение. Сырой confirmation-токен в БД не хранится
 * (только SHA-256), а API его не возвращает — поэтому для проверки эндпоинта тест сам засевает
 * токен с известным сырым значением и его хэшем, затем дергает {@code /auth/confirm}.
 */
class AuthControllerIT extends AbstractIntegrationTest {

    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "password1";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private EmailConfirmationTokenRepository confirmationTokenRepository;
    @Autowired
    private OutboxRepository outboxRepository;

    @BeforeEach
    void clean() {
        // Дети (FK на users) — до самих users; outbox без FK, но чистим для изоляции.
        refreshTokenRepository.deleteAll();
        confirmationTokenRepository.deleteAll();
        outboxRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registerShouldReturn201AndPersistDisabledUser() {
        ResponseEntity<Void> resp = rest.postForEntity(
                "/auth/register", new RegisterRequest(EMAIL, "alice", PASSWORD), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        User saved = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(saved.isEnabled()).isFalse();
    }

    @Test
    void registerShouldReturn409OnDuplicateEmail() {
        register(EMAIL, "alice", PASSWORD);

        ResponseEntity<String> resp = rest.postForEntity(
                "/auth/register", new RegisterRequest(EMAIL, "bob", "password2"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registerShouldReturn400OnInvalidPayload() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/auth/register", new RegisterRequest("not-an-email", "ab", "short"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void loginShouldReturn403BeforeEmailConfirmed() {
        register(EMAIL, "alice", PASSWORD);

        ResponseEntity<String> resp = rest.postForEntity(
                "/auth/login", new LoginRequest(EMAIL, PASSWORD), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void confirmShouldEnableUserThenLoginReturnsTokens() {
        register(EMAIL, "alice", PASSWORD);
        confirm(EMAIL);

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().isEnabled()).isTrue();

        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                "/auth/login", new LoginRequest(EMAIL, PASSWORD), TokenResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().accessToken()).isNotBlank();
        assertThat(resp.getBody().refreshToken()).isNotBlank();
        assertThat(resp.getBody().tokenType()).isEqualTo("Bearer");
    }

    @Test
    void confirmShouldReturn400OnUnknownToken() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/auth/confirm", new ConfirmRequest("garbage-token"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void loginShouldReturn401OnWrongPassword() {
        register(EMAIL, "alice", PASSWORD);
        confirm(EMAIL);

        ResponseEntity<String> resp = rest.postForEntity(
                "/auth/login", new LoginRequest(EMAIL, "wrongpass"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshShouldRotateTokenAndRevokeOldOne() {
        register(EMAIL, "alice", PASSWORD);
        confirm(EMAIL);
        TokenResponse first = rest.postForEntity(
                "/auth/login", new LoginRequest(EMAIL, PASSWORD), TokenResponse.class).getBody();
        assertThat(first).isNotNull();

        ResponseEntity<TokenResponse> refreshed = rest.postForEntity(
                "/auth/refresh", new RefreshRequest(first.refreshToken()), TokenResponse.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody()).isNotNull();
        assertThat(refreshed.getBody().refreshToken()).isNotEqualTo(first.refreshToken());

        // Старый refresh-токен отозван при ротации → повторное использование запрещено.
        ResponseEntity<String> reuse = rest.postForEntity(
                "/auth/refresh", new RefreshRequest(first.refreshToken()), String.class);

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshShouldReturn401OnUnknownToken() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/auth/refresh", new RefreshRequest("nonexistent-token"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void register(String email, String username, String password) {
        ResponseEntity<Void> resp = rest.postForEntity(
                "/auth/register", new RegisterRequest(email, username, password), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * Засевает подтверждающий токен с известным сырым значением (его хэш кладём в БД так же,
     * как это делает сервис) и подтверждает аккаунт через реальный REST-эндпоинт.
     */
    private void confirm(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        String rawToken = "raw-" + UUID.randomUUID();

        EmailConfirmationToken token = new EmailConfirmationToken();
        token.setUserId(user.getId());
        token.setTokenHash(sha256Hex(rawToken));
        token.setExpiresAt(OffsetDateTime.now().plusHours(1));
        token.setUsed(false);
        confirmationTokenRepository.save(token);

        ResponseEntity<Void> resp = rest.postForEntity(
                "/auth/confirm", new ConfirmRequest(rawToken), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
