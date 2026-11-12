package com.onfinapp.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/** The token checks, exercised with locally signed tokens. */
class TokenValidationTest {

    private static final String ISSUER = "http://localhost:8082/realms/onfinapp";
    private static final String AUDIENCE = "onfinapp-api";

    private static KeyPair key;
    private static KeyPair otherKey;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void createKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
        // In the running API the keys come from the provider's JWKS; the validator is the same.
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.getPublic()).build();
        decoder.setJwtValidator(SecurityConfig.tokenValidator(ISSUER, AUDIENCE));
    }

    private static JWTClaimsSet claims(String issuer, List<String> audience, long expiresInSeconds) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user-1")
                .issueTime(Date.from(now.minusSeconds(600)))
                .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)));
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }

    private static String token(JWTClaimsSet claims, KeyPair signedWith) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) signedWith.getPrivate()));
        return jwt.serialize();
    }

    private static String token(String issuer, List<String> audience, long expiresInSeconds) throws Exception {
        return token(claims(issuer, audience, expiresInSeconds), key);
    }

    @Test
    void aValidTokenIsAccepted() throws Exception {
        Jwt jwt = decoder.decode(token(ISSUER, List.of(AUDIENCE), 300));

        assertThat(jwt.getSubject()).isEqualTo("user-1");
    }

    @Test
    void aTokenWithSeveralAudiencesIsAcceptedWhenOursIsAmongThem() throws Exception {
        assertThat(decoder.decode(token(ISSUER, List.of("account", AUDIENCE), 300))).isNotNull();
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        String token = token("http://localhost:8082/realms/other", List.of(AUDIENCE), 300);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class).hasMessageContaining("iss");
    }

    @Test
    void aTokenForAnotherAudienceIsRejected() throws Exception {
        String token = token(ISSUER, List.of("some-other-api"), 300);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class).hasMessageContaining("aud");
    }

    @Test
    void aTokenWithoutAnAudienceIsRejected() throws Exception {
        String token = token(ISSUER, null, 300);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class).hasMessageContaining("aud");
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String token = token(ISSUER, List.of(AUDIENCE), -120);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class).hasMessageContaining("expired");
    }

    @Test
    void aTokenThatExpiredWithinTheClockAllowanceIsStillAccepted() throws Exception {
        assertThat(decoder.decode(token(ISSUER, List.of(AUDIENCE), -30))).isNotNull();
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() throws Exception {
        String token = token(claims(ISSUER, List.of(AUDIENCE), 300), otherKey);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void anUnsignedTokenIsRejected() {
        String token = new PlainJWT(claims(ISSUER, List.of(AUDIENCE), 300)).serialize();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
