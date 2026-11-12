package com.onfinapp.api.auth;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.onfinapp.api.common.CorrelationFilter;

/** The API is a resource server only: it validates tokens issued by the OIDC provider. */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String UNAUTHORIZED_BODY = """
            {"type":"about:blank","title":"Unauthorized","status":401,"detail":"A valid bearer token is required."}""";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
            // Says why (no token, expired, wrong audience, bad signature). The token itself is never logged.
            log.info("Request not authenticated: {}", ex.getMessage());
            writeUnauthorized(response);
        };
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", CorrelationFilter.HEADER));
        cors.setExposedHeaders(List.of(CorrelationFilter.HEADER));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${oidc.issuer}") String issuer,
            @Value("${oidc.audience}") String audience,
            @Value("${oidc.jwks-uri:}") String jwksUri) {
        // An explicit JWKS URL is for when the issuer URL is not reachable from here;
        // otherwise keys are found through OIDC discovery.
        NimbusJwtDecoder decoder = jwksUri.isBlank()
                ? NimbusJwtDecoder.withIssuerLocation(issuer).build()
                : NimbusJwtDecoder.withJwkSetUri(jwksUri).build();

        decoder.setJwtValidator(tokenValidator(issuer, audience));
        return decoder;
    }

    /**
     * What a token must satisfy besides a valid signature: issued by the configured issuer, meant for this
     * API (audience), and not expired. The same checks, with the same one-minute allowance for clock
     * differences, as the .NET API.
     */
    public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", aud -> aud != null && aud.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }

    private static void writeUnauthorized(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
