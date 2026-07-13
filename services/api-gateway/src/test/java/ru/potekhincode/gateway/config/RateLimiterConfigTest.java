package ru.potekhincode.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;

/**
 * Юнит-тесты гибридного {@link KeyResolver}: ключ лимита — userId из JWT для
 * аутентифицированных, IP-адрес для анонимных (публичные пути `/auth/**`).
 */
class RateLimiterConfigTest {

    private final KeyResolver resolver = new RateLimiterConfig().userOrIpKeyResolver();

    @Test
    void authenticatedRequestIsKeyedByUserId() {
        String userId = "8f1b6c2e-0000-4000-8000-000000000001";

        StepVerifier.create(resolver.resolve(exchange())
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuth(userId))))
                .expectNext("user:" + userId)
                .verifyComplete();
    }

    @Test
    void requestWithoutSecurityContextIsKeyedByIp() {
        StepVerifier.create(resolver.resolve(exchange()))
                .expectNext("ip:10.1.2.3")
                .verifyComplete();
    }

    /**
     * Анонимный токен «аутентифицирован» с точки зрения {@code isAuthenticated()} —
     * без явной проверки типа все анонимы схлопнулись бы в один бакет `user:anonymousUser`.
     */
    @Test
    void anonymousRequestIsKeyedByIp() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        StepVerifier.create(resolver.resolve(exchange())
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(anonymous)))
                .expectNext("ip:10.1.2.3")
                .verifyComplete();
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/products")
                .remoteAddress(new InetSocketAddress("10.1.2.3", 51000)));
    }

    private JwtAuthenticationToken jwtAuth(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("role", "ROLE_USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
