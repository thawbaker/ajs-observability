package com.example.ajs.auth.security;

import com.example.ajs.auth.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for JWT issuing and verification.
 *
 * <p>The tampering cases are the point. During development, appending a
 * character to the signature segment of a valid token was <em>accepted</em> by
 * jjwt 0.12.6, because it base64url-decodes the signature before comparing and
 * silently discards the padding bits the extra character contributes. That was
 * measured directly, and {@link JwtService} now enforces canonical segment form
 * to close it. These tests pin that behaviour so it cannot regress.
 */
class JwtServiceTest {

    private static final String SECRET =
            "a-test-secret-that-is-comfortably-longer-than-32-bytes-for-hs256";

    private final JwtService jwtService = new JwtService(SECRET);

    private static User user(long id, User.Role role, boolean disabled) {
        return new User(id, "user@example.com", "$2a$10$unused", "Test User",
                role, disabled, "2025-01-01T00:00:00");
    }

    @Nested
    @DisplayName("issuing")
    class Issuing {

        @Test
        void roundTripsIdentityAndRole() {
            String token = jwtService.createToken(user(42, User.Role.ADMIN, false));

            var claims = jwtService.verify(token);
            assertThat(claims).isPresent();
            assertThat(claims.get().userId()).isEqualTo(42);
            assertThat(claims.get().email()).isEqualTo("user@example.com");
            assertThat(claims.get().name()).isEqualTo("Test User");
            assertThat(claims.get().role()).isEqualTo(User.Role.ADMIN);
        }

        @Test
        @DisplayName("disabling the account does NOT change the token, because the DB is authoritative")
        void disabledIsNotEncodedInToken() {
            String enabled = jwtService.createToken(user(1, User.Role.USER, false));
            String disabled = jwtService.createToken(user(1, User.Role.USER, true));

            // Both verify: the token says nothing about disabled state. That is
            // intentional. Disabling must take effect via the per-request DB
            // re-check in JwtAuthenticationFilter, not by waiting for expiry.
            assertThat(jwtService.verify(enabled)).isPresent();
            assertThat(jwtService.verify(disabled)).isPresent();
        }

        @Test
        void tokenHasThreeSegments() {
            String token = jwtService.createToken(user(1, User.Role.USER, false));
            assertThat(token.split("\\.")).hasSize(3);
        }
    }

    @Nested
    @DisplayName("verification rejects")
    class Verification {

        @Test
        void nullAndBlank() {
            assertThat(jwtService.verify(null)).isEmpty();
            assertThat(jwtService.verify("")).isEmpty();
            assertThat(jwtService.verify("   ")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "not-a-jwt",
                "a.b",
                "a.b.c.d",
                "...",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0", // two segments, unsigned
        })
        void structurallyInvalidTokens(String token) {
            assertThat(jwtService.verify(token)).isEmpty();
        }

        @Test
        @DisplayName("a token signed with a different secret")
        void wrongSecret() {
            JwtService other = new JwtService(
                    "a-completely-different-secret-also-longer-than-32-bytes!!");
            String foreign = other.createToken(user(1, User.Role.ADMIN, false));

            assertThat(jwtService.verify(foreign)).isEmpty();
        }

        @Test
        @DisplayName("a flipped character inside the signature")
        void flippedSignatureCharacter() {
            String token = jwtService.createToken(user(1, User.Role.ADMIN, false));
            String[] parts = token.split("\\.");
            char first = parts[2].charAt(0);
            String flipped = parts[0] + "." + parts[1] + "."
                    + (first == 'A' ? 'B' : 'A') + parts[2].substring(1);

            assertThat(jwtService.verify(flipped)).isEmpty();
        }

        @Test
        @DisplayName("characters APPENDED to the signature — the real jjwt gap")
        void appendedToSignature() {
            String token = jwtService.createToken(user(1, User.Role.ADMIN, false));

            // This is the case that jjwt alone accepts. It must not pass here.
            assertThat(jwtService.verify(token + "x"))
                    .as("appending to the signature must be rejected: jjwt decodes "
                            + "the signature and drops the extra padding bits, so "
                            + "canonical-form checking is required")
                    .isEmpty();
            assertThat(jwtService.verify(token + "xxxx")).isEmpty();
        }

        @Test
        @DisplayName("a forged payload claiming admin, carrying the original signature")
        void forgedPayload() {
            String token = jwtService.createToken(user(1, User.Role.USER, false));
            String[] parts = token.split("\\.");

            String forged = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"sub\":\"1\",\"role\":\"admin\"}".getBytes());

            assertThat(jwtService.verify(parts[0] + "." + forged + "." + parts[2])).isEmpty();
        }

        @Test
        @DisplayName("an expired token")
        void expiredToken() {
            // A separate service with the same key, issuing an already-expired
            // token, so expiry is exercised without sleeping.
            String expired = io.jsonwebtoken.Jwts.builder()
                    .subject("1")
                    .claim("role", "admin")
                    .issuedAt(new java.util.Date(System.currentTimeMillis() - 7_200_000))
                    .expiration(new java.util.Date(System.currentTimeMillis() - 3_600_000))
                    .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                            SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            io.jsonwebtoken.Jwts.SIG.HS256)
                    .compact();

            assertThat(jwtService.verify(expired)).isEmpty();
        }

        @Test
        @DisplayName("a token with empty segments")
        void emptySegments() {
            String token = jwtService.createToken(user(1, User.Role.USER, false));
            String[] parts = token.split("\\.");

            assertThat(jwtService.verify(parts[0] + ".." + parts[2])).isEmpty();
            assertThat(jwtService.verify("." + parts[1] + "." + parts[2])).isEmpty();
        }
    }

    @Nested
    @DisplayName("construction refuses unsafe configuration")
    class Construction {

        @Test
        @DisplayName("a blank secret is refused rather than falling back to a default")
        void blankSecretRefused() {
            // A default secret in source is how every session token becomes
            // forgeable by anyone who has read the repository.
            assertThatThrownBy(() -> new JwtService(""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT secret is not configured");
            assertThatThrownBy(() -> new JwtService(null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a secret shorter than 32 bytes is refused for HS256")
        void shortSecretRefused() {
            assertThatThrownBy(() -> new JwtService("too-short"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 32 bytes");
        }

        @Test
        void exactly32BytesIsAccepted() {
            assertThat(new JwtService("01234567890123456789012345678901")).isNotNull();
        }
    }
}
