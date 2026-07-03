package ru.potekhincode.auth.security;

import org.junit.jupiter.api.Test;
import ru.potekhincode.auth.security.GithubEmailOAuth2UserService.GithubEmail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты выбора email из ответа GitHub {@code /user/emails}
 * (правило: primary+verified в приоритете, иначе первый verified, иначе null).
 */
class GithubEmailOAuth2UserServiceTest {

    @Test
    void shouldPickPrimaryVerifiedEmail() {
        List<GithubEmail> emails = List.of(
                new GithubEmail("secondary@example.com", false, true),
                new GithubEmail("primary@example.com", true, true));

        assertThat(GithubEmailOAuth2UserService.selectPrimaryVerifiedEmail(emails))
                .isEqualTo("primary@example.com");
    }

    @Test
    void shouldFallBackToVerifiedWhenNoPrimaryVerified() {
        List<GithubEmail> emails = List.of(
                new GithubEmail("primary-unverified@example.com", true, false),
                new GithubEmail("verified@example.com", false, true));

        assertThat(GithubEmailOAuth2UserService.selectPrimaryVerifiedEmail(emails))
                .isEqualTo("verified@example.com");
    }

    @Test
    void shouldReturnNullWhenNoneVerified() {
        List<GithubEmail> emails = List.of(
                new GithubEmail("a@example.com", true, false),
                new GithubEmail("b@example.com", false, false));

        assertThat(GithubEmailOAuth2UserService.selectPrimaryVerifiedEmail(emails)).isNull();
    }

    @Test
    void shouldReturnNullOnNullList() {
        assertThat(GithubEmailOAuth2UserService.selectPrimaryVerifiedEmail(null)).isNull();
    }
}
