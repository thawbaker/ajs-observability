package com.example.ajs.auth.security;

import com.example.ajs.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Issues and verifies the session JWT, mirroring {@code createToken} /
 * {@code verifyToken} in {@code my-dashboard/src/lib/auth.ts}.
 *
 * <p>Design points carried over from the reference implementation:
 * <ul>
 *   <li>The token carries id, email, name and role. The role is in the token so
 *       expensive gating can happen without a query.</li>
 *   <li>The token is <strong>not</strong> authoritative. Every request re-reads
 *       the user and rejects a disabled or deleted account, so disabling a user
 *       ends their session immediately rather than 24 hours later.</li>
 *   <li>HS256 with a symmetric key, 24-hour expiry, matching the reference.</li>
 * </ul>
 *
 * <h2>Hardening beyond the reference implementation</h2>
 * Segment canonicalisation is enforced before the signature is verified. This
 * matters: jjwt base64url-decodes the signature before comparing it, so
 * characters appended to the signature segment are silently discarded during
 * decoding and <em>the token verifies successfully</em>. That was measured, not
 * theorised — appending {@code "x"} to a valid token was accepted by jjwt
 * 0.12.6 on its own. Re-encoding each segment and requiring an exact match
 * closes it, because a segment with discarded padding bits cannot round-trip.
 *
 * <p>Forging a payload or flipping a signature character was already rejected by
 * the signature check; this closes the remaining acceptance gap.
 */
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** base64url alphabet, no padding — the only characters a segment may use. */
    private static final Pattern B64URL = Pattern.compile("^[A-Za-z0-9_-]+$");

    /** Cookie name, matching the reference implementation. */
    public static final String COOKIE_NAME = "auth-token";

    /** 24 hours, matching the reference implementation. */
    public static final long TOKEN_TTL_MILLIS = 24L * 60 * 60 * 1000;

    /** HS256 requires at least a 256-bit key. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;

    public JwtService(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. Set the JWT_SECRET environment variable. "
                            + "Refusing to start with a blank secret: it would make every session "
                            + "token forgeable by anyone who knows the default.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256; got "
                            + bytes.length + ". A short secret is brute-forceable offline.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** Issues a token for the user. {@code disabled} is deliberately not included. */
    public String createToken(User user) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(Long.toString(user.id()))
                .claim("email", user.email())
                .claim("name", user.name())
                .claim("role", user.role().dbValue())
                .issuedAt(new Date(now))
                .expiration(new Date(now + TOKEN_TTL_MILLIS))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies a token and returns its claims.
     *
     * @return empty for any invalid, malformed, non-canonical, tampered or
     *         expired token. Never throws — callers get a boolean-like answer so
     *         an authentication failure cannot accidentally become a 500.
     */
    public Optional<SessionClaims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            requireCanonicalSegments(token);
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String subject = claims.getSubject();
            if (subject == null) {
                return Optional.empty();
            }
            long id = Long.parseLong(subject);
            return Optional.of(new SessionClaims(
                    id,
                    claims.get("email", String.class),
                    claims.get("name", String.class),
                    User.Role.from(claims.get("role", String.class))));
        } catch (ExpiredJwtException ex) {
            log.debug("Rejected expired session token");
            return Optional.empty();
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected invalid session token: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Rejects any token whose segments are not canonical base64url.
     *
     * <p>Without this, a token with extra characters appended to the signature
     * segment verifies: the decoder drops the padding bits that the extra
     * characters contribute, producing the same signature bytes. Verified
     * against jjwt 0.12.6.
     */
    private void requireCanonicalSegments(String token) {
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) {
            throw new MalformedJwtException("Expected 3 segments, got " + segments.length);
        }
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty() || !B64URL.matcher(segments[i]).matches()) {
                throw new MalformedJwtException("Segment " + i + " is not valid base64url");
            }
            String reencoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Base64.getUrlDecoder().decode(segments[i]));
            if (!reencoded.equals(segments[i])) {
                throw new MalformedJwtException("Segment " + i + " is not in canonical form");
            }
        }
    }

    /** The authenticated identity carried by a verified token. */
    public record SessionClaims(long userId, String email, String name, User.Role role) {
    }
}
