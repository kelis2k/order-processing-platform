package ru.potekhincode.auth.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import ru.potekhincode.auth.model.RefreshToken;
import ru.potekhincode.auth.model.Role;
import ru.potekhincode.auth.model.User;
import ru.potekhincode.auth.outbox.OutboxEventFactory;
import ru.potekhincode.auth.repository.EmailConfirmationTokenRepository;
import ru.potekhincode.auth.repository.OutboxRepository;
import ru.potekhincode.auth.repository.RefreshTokenRepository;
import ru.potekhincode.auth.repository.UserRepository;
import ru.potekhincode.auth.security.JwtService;
import ru.potekhincode.auth.service.AuthService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final EmailConfirmationTokenRepository confirmationTokenRepository;
    private final Duration confirmationTtl;
    private final OutboxEventFactory outboxEventFactory;
    private final OutboxRepository outboxRepository;

    public AuthServiceImpl(UserRepository userRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           JwtProperties jwtProperties,
                           OutboxEventFactory outboxEventFactory,
                           OutboxRepository outboxRepository,
                           EmailConfirmationTokenRepository confirmationTokenRepository,
                           @org.springframework.beans.factory.annotation.Value("${app.confirmation.token-ttl}")
                           Duration confirmationTtl) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.outboxEventFactory = outboxEventFactory;
        this.outboxRepository = outboxRepository;
        this.confirmationTokenRepository = confirmationTokenRepository;
        this.confirmationTtl = confirmationTtl;
    }

    @Override
    @Transactional
    public void register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyUsedException(request.email());
        }

        User user = new User();
        user.setEmail(request.email());
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.ROLE_USER);
        user.setEnabled(false);
        userRepository.save(user);
        outboxRepository.save(outboxEventFactory.userCreated(user));

        String rawToken = generateOpaqueToken();
        EmailConfirmationToken token = new EmailConfirmationToken();
        token.setUserId(user.getId());
        token.setTokenHash(sha256Hex(rawToken));
        token.setExpiresAt(OffsetDateTime.now().plus(confirmationTtl));
        token.setUsed(false);
        confirmationTokenRepository.save(token);

        outboxRepository.save(outboxEventFactory.userConfirmationRequested(
                user, rawToken, token.getExpiresAt().toInstant().toEpochMilli()));
    }

    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        if (!user.isEnabled()) {
            throw new EmailNotConfirmedException();
        }

        return issueTokens(user);
    }

    @Override
    @Transactional
    public TokenResponse refresh(RefreshRequest request) {
        String hash = sha256Hex(request.refreshToken());

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (stored.isRevoked() || stored.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InvalidRefreshTokenException();
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(InvalidRefreshTokenException::new);

        stored.setRevoked(true);
        return issueTokens(user);
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshTokenValue = generateOpaqueToken();

        RefreshToken entity = new RefreshToken();
        entity.setUserId(user.getId());
        entity.setTokenHash(sha256Hex(refreshTokenValue));
        entity.setExpiresAt(OffsetDateTime.now().plus(jwtProperties.refreshTtl()));
        entity.setRevoked(false);
        refreshTokenRepository.save(entity);

        return new TokenResponse(
                accessToken,
                refreshTokenValue,
                "Bearer",
                jwtProperties.accessTtl().toSeconds());

    }

    private static String generateOpaqueToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest); // 64 hex-символа → влезает в VARCHAR(64)
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    @Transactional
    public void confirm(ConfirmRequest request) {
        String hash = sha256Hex(request.token());

        EmailConfirmationToken token = confirmationTokenRepository.findByTokenHash(hash)
                .orElseThrow(InvalidConfirmationTokenException::new);

        if (token.isUsed() || token.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InvalidConfirmationTokenException();
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(InvalidConfirmationTokenException::new);

        user.setEnabled(true);
        token.setUsed(true);
    }

    @Override
    @Transactional
    public TokenResponse oauthLogin(String email,
                                    String username,
                                    AuthProvider provider,
                                    String providerId) {
        User user = userRepository.findByEmail(email)
                .orElseGet(() -> createOAuthUser(email, username, provider, providerId));
        return issueTokens(user);
    }

    private User createOAuthUser(String email, String username, AuthProvider provider, String providerId) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(username);
        user.setPasswordHash(null);
        user.setRole(Role.ROLE_USER);
        user.setEnabled(true);
        user.setProvider(provider);
        user.setProviderId(providerId);
        userRepository.save(user);
        outboxRepository.save(outboxEventFactory.userCreated(user));
        return user;
    }

    @Override
    @Transactional
    public void applyRoleChange(UUID userId, Role role) {
        userRepository.findById(userId).ifPresentOrElse(
                user -> {
                    user.setRole(role);
                    log.info("Applied role change from user.role-changed: userId={}, role={}", userId, role);
                },
                () -> log.warn("Received user.role-changed for unknown userId={}, skipping", userId)
        );
    }
}
