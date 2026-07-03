package ru.potekhincode.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import ru.potekhincode.auth.dto.TokenResponse;
import ru.potekhincode.auth.model.AuthProvider;
import ru.potekhincode.auth.service.AuthService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты веб-половины OAuth-моста: перевод атрибутов провайдера (google/github)
 * в аргументы {@link AuthService#oauthLogin} и сериализация {@link TokenResponse} в ответ.
 * Реальные {@link OAuth2AuthenticationToken}/{@link DefaultOAuth2User} — чтобы проверять именно
 * то, как Spring кладёт атрибуты; ответ ловится {@link MockHttpServletResponse}.
 */
@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    @Mock
    private AuthService authService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OAuth2LoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2LoginSuccessHandler(authService, objectMapper);
    }

    @Test
    void shouldMapGoogleAttributesAndWriteTokenJson() throws Exception {
        OAuth2AuthenticationToken token = providerToken(
                Map.of("email", "bob@example.com", "name", "Bob", "sub", "google-sub-1"),
                "sub", "google");
        when(authService.oauthLogin("bob@example.com", "Bob", AuthProvider.GOOGLE, "google-sub-1"))
                .thenReturn(new TokenResponse("ACCESS", "REFRESH", "Bearer", 900));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, token);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"accessToken\":\"ACCESS\"");
    }

    @Test
    void shouldStringifyGithubNumericId() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("email", "bob@example.com");
        attrs.put("login", "bob");
        attrs.put("id", 42);                               // GitHub id — число
        OAuth2AuthenticationToken token = providerToken(attrs, "login", "github");
        when(authService.oauthLogin("bob@example.com", "bob", AuthProvider.GITHUB, "42"))
                .thenReturn(new TokenResponse("A", "R", "Bearer", 900));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, token);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(authService).oauthLogin("bob@example.com", "bob", AuthProvider.GITHUB, "42");
    }

    @Test
    void shouldReturn400AndNotLoginWhenEmailMissing() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("login", "bob");
        attrs.put("id", 42);                               // email отсутствует (приватный на GitHub)
        OAuth2AuthenticationToken token = providerToken(attrs, "login", "github");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, token);

        assertThat(response.getStatus()).isEqualTo(400);
        verify(authService, never()).oauthLogin(any(), any(), any(), any());
    }

    @Test
    void shouldReturn400OnUnsupportedProvider() throws Exception {
        OAuth2AuthenticationToken token = providerToken(
                Map.of("email", "x@y.z", "sub", "s"), "sub", "twitter");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, token);

        assertThat(response.getStatus()).isEqualTo(400);
        verify(authService, never()).oauthLogin(any(), any(), any(), any());
    }

    private OAuth2AuthenticationToken providerToken(Map<String, Object> attrs,
                                                    String nameKey, String registrationId) {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), attrs, nameKey);
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), registrationId);
    }
}
