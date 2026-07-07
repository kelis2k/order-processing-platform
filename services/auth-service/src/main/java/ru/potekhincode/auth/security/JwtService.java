package ru.potekhincode.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import ru.potekhincode.auth.config.JwtProperties;
import ru.potekhincode.auth.model.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import java.util.Map;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final RSAKey rsaJwk;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.privateKey = loadPrivateKey(properties.privateKey());
        this.publicKey = loadPublicKey(properties.publicKey());
        this.rsaJwk = buildRsaJwk((RSAPublicKey) this.publicKey);


    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(rsaJwk.getKeyID()).and()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTtl())))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

    }

    public Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token);

    }

    private PrivateKey loadPrivateKey(Resource resource) {
        try {
            byte[] der = pemToDer(resource);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load JWT private key", e);
        }

    }

    private byte[] pemToDer(Resource resource) throws Exception {
        String pem = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String base64 = pem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private PublicKey loadPublicKey(Resource resource) {
        try {
            byte[] der = pemToDer(resource);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load JWT public key", e);
        }
    }

    private RSAKey buildRsaJwk(RSAPublicKey pub) {
        try {
            return new RSAKey.Builder(pub)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot build JWK from public key", e);
        }
    }

    public Map<String, Object> jwks() {
        return new JWKSet(rsaJwk.toPublicJWK()).toJSONObject();
    }
}
