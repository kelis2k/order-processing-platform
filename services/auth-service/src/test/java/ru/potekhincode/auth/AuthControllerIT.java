package ru.potekhincode.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import ru.potekhincode.auth.dto.LoginRequest;
import ru.potekhincode.auth.dto.RefreshRequest;
import ru.potekhincode.auth.dto.RegisterRequest;
import ru.potekhincode.auth.dto.TokenResponse;
import ru.potekhincode.auth.repository.RefreshTokenRepository;
import ru.potekhincode.auth.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной IT auth-service на полном Spring-контексте + Testcontainers PostgreSQL (с Flyway).
 * REST — настоящая точка входа: проверяются {@code /auth/register|login|refresh} через HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerIT {

    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "password1";

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
                    .withDatabaseName("auth_db");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void clean() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registerShouldReturn201AndPersistUser() {
        ResponseEntity<Void> resp = rest.postForEntity(
                "/auth/register", new RegisterRequest(EMAIL, "alice", PASSWORD), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(userRepository.existsByEmail(EMAIL)).isTrue();
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
    void loginShouldReturnTokens() {
        register(EMAIL, "alice", PASSWORD);

        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                "/auth/login", new LoginRequest(EMAIL, PASSWORD), TokenResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().accessToken()).isNotBlank();
        assertThat(resp.getBody().refreshToken()).isNotBlank();
        assertThat(resp.getBody().tokenType()).isEqualTo("Bearer");
    }

    @Test
    void loginShouldReturn401OnWrongPassword() {
        register(EMAIL, "alice", PASSWORD);

        ResponseEntity<String> resp = rest.postForEntity(
                "/auth/login", new LoginRequest(EMAIL, "wrongpass"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshShouldRotateTokenAndRevokeOldOne() {
        register(EMAIL, "alice", PASSWORD);
        TokenResponse first = rest.postForEntity(
                "/auth/login", new LoginRequest(EMAIL, PASSWORD), TokenResponse.class).getBody();
        assertThat(first).isNotNull();

        ResponseEntity<TokenResponse> refreshed = rest.postForEntity(
                "/auth/refresh", new RefreshRequest(first.refreshToken()), TokenResponse.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody()).isNotNull();
        assertThat(refreshed.getBody().refreshToken()).isNotEqualTo(first.refreshToken());

        // Старый refresh-токен отозван при ротации → повторное использование запрещено.
        // Без @Transactional на refresh() отзыв не сохранялся бы и тут пришёл бы 200.
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
}
