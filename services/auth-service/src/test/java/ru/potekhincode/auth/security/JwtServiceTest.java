package ru.potekhincode.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import ru.potekhincode.auth.config.JwtProperties;
import ru.potekhincode.auth.model.Role;
import ru.potekhincode.auth.model.User;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Юнит-тест {@link JwtService} на реальной RSA-паре из {@code classpath:keys}.
 * Round-trip generate→parse подтверждает, что и приватный (подпись), и публичный
 * (верификация) ключи загружаются корректно.
 */
class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties(
                "auth-service",
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                new ClassPathResource("keys/jwt-private.pem"),
                new ClassPathResource("keys/jwt-public.pem"));
        jwtService = new JwtService(props);
    }

    @Test
    void generatedTokenShouldBeParseableAndCarryClaims() {
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        user.setEmail("alice@example.com");
        user.setRole(Role.ROLE_USER);

        String token = jwtService.generateAccessToken(user);
        Jws<Claims> jws = jwtService.parse(token);
        Claims claims = jws.getPayload();

        assertThat(claims.getSubject()).isEqualTo(id.toString());
        assertThat(claims.getIssuer()).isEqualTo("auth-service");
        assertThat(claims.get("email", String.class)).isEqualTo("alice@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_USER");
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    @Test
    void parseShouldRejectTamperedToken() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("alice@example.com");
        user.setRole(Role.ROLE_USER);

        String token = jwtService.generateAccessToken(user);
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> jwtService.parse(tampered))
                .isInstanceOf(JwtException.class);
    }
}
