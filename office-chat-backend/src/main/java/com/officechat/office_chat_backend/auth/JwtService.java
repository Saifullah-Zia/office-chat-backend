package com.officechat.office_chat_backend.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    /** Claim holding the session id, so a token can be revoked by signing the session out. */
    public static final String SESSION_CLAIM = "sid";
    /** Marks the short-lived token issued between password and 2FA code; it can't be used to call the API. */
    private static final String MFA_CLAIM = "mfa";
    private static final long MFA_TOKEN_MS = 5 * 60 * 1000;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expirationMs;


    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(UserDetails userDetails){
        return generateToken(userDetails, null);
    }

    public String generateToken(UserDetails userDetails, String sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        var builder = Jwts.builder()
                .subject(userDetails.getUsername())
                .issuedAt(now)
                .expiration(expiry);
        if (sessionId != null) builder.claim(SESSION_CLAIM, sessionId);
        return builder.signWith(signingKey()).compact();
    }

    /** Proof that the password step succeeded; exchanged for a real token after the 2FA code. */
    public String generateMfaToken(UserDetails userDetails) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim(MFA_CLAIM, true)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + MFA_TOKEN_MS))
                .signWith(signingKey())
                .compact();
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractSessionId(String token) {
        return extractClaim(token, c -> c.get(SESSION_CLAIM, String.class));
    }

    public boolean isMfaToken(String token) {
        return Boolean.TRUE.equals(extractClaim(token, c -> c.get(MFA_CLAIM, Boolean.class)));
    }

    /** Valid API token: right user, not expired, and not a half-finished 2FA login token. */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        String email = extractEmail(token);
        return email != null && email.equalsIgnoreCase(userDetails.getUsername())
                && !isTokenExpired(token) && !isMfaToken(token);
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser()
                .clockSkewSeconds(60)
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }
}
