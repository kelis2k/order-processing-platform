package ru.potekhincode.auth.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Кастомный {@link OAuth2UserService} для не-OIDC провайдеров (GitHub).
 * <p>
 * Базовый ответ GitHub {@code GET /user} кладёт в {@code email} только <b>публичный</b>
 * адрес из профиля — при приватном email там {@code null}, и мост отдал бы 400 (ADR 0004).
 * Здесь, если email пуст, дочитываем его через {@code GET /user/emails} (на это выдан
 * scope {@code user:email}) и подставляем primary+verified адрес как атрибут {@code email}.
 * <p>
 * Google идёт через {@code OidcUserService} (scope {@code openid}), сюда не попадает.
 */
@Slf4j
@Component
public class GithubEmailOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final String GITHUB_REGISTRATION_ID = "github";
    private static final String EMAIL_ATTRIBUTE = "email";
    private static final String EMAILS_URI = "https://api.github.com/user/emails";

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final RestClient restClient = RestClient.create();

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User user = delegate.loadUser(userRequest);

        boolean isGithub = GITHUB_REGISTRATION_ID.equals(
                userRequest.getClientRegistration().getRegistrationId());
        if (!isGithub || user.getAttribute(EMAIL_ATTRIBUTE) != null) {
            return user; // не GitHub или email уже есть — ничего не делаем
        }

        String primaryEmail = fetchPrimaryEmail(userRequest.getAccessToken().getTokenValue());
        if (primaryEmail == null) {
            return user; // не удалось — мост отдаст 400 (прежнее поведение)
        }

        Map<String, Object> attributes = new HashMap<>(user.getAttributes());
        attributes.put(EMAIL_ATTRIBUTE, primaryEmail);

        String nameAttributeKey = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
        return new DefaultOAuth2User(user.getAuthorities(), attributes, nameAttributeKey);
    }

    private String fetchPrimaryEmail(String accessToken) {
        try {
            List<GithubEmail> emails = restClient.get()
                    .uri(EMAILS_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GithubEmail>>() {});
            return selectPrimaryVerifiedEmail(emails);
        } catch (RuntimeException e) {
            log.warn("Failed to fetch GitHub emails via {}: {}", EMAILS_URI, e.getMessage());
            return null;
        }
    }

    /** primary+verified в приоритете; иначе первый verified; иначе null. */
    static String selectPrimaryVerifiedEmail(List<GithubEmail> emails) {
        if (emails == null) {
            return null;
        }
        return emails.stream()
                .filter(e -> e.primary() && e.verified())
                .map(GithubEmail::email)
                .findFirst()
                .orElseGet(() -> emails.stream()
                        .filter(GithubEmail::verified)
                        .map(GithubEmail::email)
                        .findFirst()
                        .orElse(null));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GithubEmail(String email, boolean primary, boolean verified) {
    }
}
