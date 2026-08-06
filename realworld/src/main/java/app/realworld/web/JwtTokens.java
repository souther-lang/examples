// JWT issuance and verification. This is the part of authentication the domain does not see at all:
// identity.sou decides whether a password matches, and a token is how the boundary carries that
// answer to the next request. No .sou names a token.
package app.realworld.web;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

public final class JwtTokens {

    private static final Duration LIFETIME = Duration.ofDays(1);

    private final SecretKey key;

    /** The secret must be at least 32 bytes for HS256; the property carries a development default. */
    public JwtTokens(String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** The username is the subject: it is the only thing a later request needs to know who is asking. */
    public String issue(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(LIFETIME)))
                .signWith(key)
                .compact();
    }

    /**
     * The username a token vouches for, or nothing. A bad signature, an expired token and a malformed
     * string are all the same answer here: the request has no viewer, and whether that is a 401 is
     * decided by the endpoint rather than by this.
     */
    public Optional<String> usernameOf(String jwt) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
