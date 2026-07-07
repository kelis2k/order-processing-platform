package ru.potekhincode.user.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.potekhincode.user.AbstractIntegrationTest;
import ru.potekhincode.user.model.Role;
import ru.potekhincode.user.model.UserProfile;
import ru.potekhincode.user.repository.OutboxRepository;
import ru.potekhincode.user.repository.UserProfileRepository;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT правил авторизации user-service (шаг 5.5.4): resource-server + method-security.
 * <ul>
 *   <li>{@code GET /users} — только аутентифицированный;</li>
 *   <li>{@code PATCH /users/{id}} — владелец ({@code sub == id}) или ADMIN (@PreAuthorize);</li>
 *   <li>{@code PUT /users/{id}/role} — только ADMIN.</li>
 * </ul>
 */
class UserAuthorizationIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserProfileRepository profileRepository;
    @Autowired
    private OutboxRepository outboxRepository;

    private UUID userId;

    @BeforeEach
    void seed() {
        outboxRepository.deleteAll();
        profileRepository.deleteAll();
        userId = UUID.randomUUID();
        UserProfile profile = new UserProfile();
        profile.setId(userId);
        profile.setEmail("authz-" + userId + "@example.com");
        profile.setUsername("authz_user");
        profile.setRole(Role.ROLE_USER);
        profileRepository.save(profile);
    }

    @Test
    void listWithoutTokenIsUnauthorized() {
        ResponseEntity<Void> resp = rest.getForEntity("/users", Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listWithUserTokenIsOk() {
        ResponseEntity<Void> resp = rest.exchange("/users", HttpMethod.GET,
                new HttpEntity<>(bearer("someone", "ROLE_USER")), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void patchOwnProfileIsOk() {
        ResponseEntity<Void> resp = rest.exchange("/users/" + userId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("username", "renamed_self"), bearer(userId.toString(), "ROLE_USER")),
                Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void patchOtherProfileIsForbidden() {
        ResponseEntity<Void> resp = rest.exchange("/users/" + userId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("username", "hacker"), bearer(UUID.randomUUID().toString(), "ROLE_USER")),
                Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void changeRoleWithUserTokenIsForbidden() {
        ResponseEntity<Void> resp = rest.exchange("/users/" + userId + "/role", HttpMethod.PUT,
                new HttpEntity<>(Map.of("role", "ROLE_ADMIN"), bearer("someone", "ROLE_USER")),
                Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void changeRoleWithAdminTokenIsOk() {
        ResponseEntity<Void> resp = rest.exchange("/users/" + userId + "/role", HttpMethod.PUT,
                new HttpEntity<>(Map.of("role", "ROLE_MANAGER"), bearer("admin-it", "ROLE_ADMIN")),
                Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
