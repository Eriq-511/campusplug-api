package com.campusplug.api.security.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.campusplug.api.auth.config.AuthProperties;
import com.campusplug.api.users.UserEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final long ttlSeconds;

    public JwtService(AuthProperties authProperties) {
        String secret = authProperties.getJwt().getSecret();
        this.ttlSeconds = authProperties.getJwt().getTtlSeconds();
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).build();
    }

    public String createToken(UserEntity user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);

        return JWT.create()
                .withIssuer("campusplug-api")
                .withSubject(String.valueOf(user.getId()))
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("email", user.getEmail())
                .withClaim("fullName", user.getFullName())
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(exp))
                .sign(algorithm);
    }

    public DecodedJWT verify(String token) throws JWTVerificationException {
        return verifier.verify(token);
    }

    public long getTokenTtlSeconds() {
        return ttlSeconds;
    }
}
