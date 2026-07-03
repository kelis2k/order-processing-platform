package ru.potekhincode.auth.security;


import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import ru.potekhincode.auth.dto.TokenResponse;
import ru.potekhincode.auth.model.AuthProvider;
import ru.potekhincode.auth.service.AuthService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler  implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String registrationId = token.getAuthorizedClientRegistrationId();
        OAuth2User principal = token.getPrincipal();

        AuthProvider provider;
        String email;
        String username;
        String providerId;

        switch (registrationId) {
            case "google" -> {
                provider = AuthProvider.GOOGLE;
                email = principal.getAttribute("email");
                username = principal.getAttribute("name");
                providerId = principal.getAttribute("sub");
            }

            case "github" -> {
                provider = AuthProvider.GITHUB;
                email = principal.getAttribute("email");
                username = principal.getAttribute("login");
                Object id = principal.getAttribute("id");
                providerId = (id == null) ? null : id.toString();
            }

            default -> {
                response.sendError(HttpStatus.BAD_REQUEST.value(),
                        "Unsupported OAuth provider: " + registrationId);
                return;
            }
        }

        if (email == null) {
            response.sendError(HttpStatus.BAD_REQUEST.value(),
                "OAuth provider did not return an email");
            return;
        }

        TokenResponse tokens = authService.oauthLogin(email, username, provider, providerId);

        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), tokens);
    }
}
