package ru.potekhincode.order.security;

import org.springframework.security.oauth2.jwt.Jwt;

public record Caller(String userId, String role) {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    public static Caller from(Jwt jwt) {
        return new Caller(jwt.getSubject(), jwt.getClaimAsString("role"));
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    public boolean owns(String ownerId) {
        return userId != null && userId.equals(ownerId);
    }
}
