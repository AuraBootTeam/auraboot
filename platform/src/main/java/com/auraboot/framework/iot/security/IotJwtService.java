package com.auraboot.framework.iot.security;

import com.auraboot.framework.meta.exception.MetaServiceException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Device JWT signing / verification for the EMQX 5 broker. Issues tokens whose
 * payload carries an {@code acl} claim shaped as
 * {@code [{action, topic}, …]} per the design doc §6 EMQX ACL Pattern.
 *
 * <p>Algorithm is chosen via {@link IotJwtProperties#getAlgorithm()}:
 * <ul>
 *   <li>{@code HS256} — symmetric, uses {@code iot.security.jwt.secret}</li>
 *   <li>{@code RS256} — asymmetric, uses {@code privateKey}/{@code publicKey}</li>
 * </ul>
 *
 * @since 2.6.0
 */
@Slf4j
@Service
@EnableConfigurationProperties(IotJwtProperties.class)
public class IotJwtService {

    /** Single device-claim envelope per design doc §6. */
    public record IotDeviceJwtClaims(
            long tenantId,
            String productKey,
            String deviceCode,
            String iotId,
            List<AclEntry> acl) {
    }

    public record AclEntry(String action, String topic) {
    }

    private final IotJwtProperties props;
    private Key signingKey;
    private Key verifyKey;
    private String algorithmName;

    public IotJwtService(IotJwtProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        String alg = props.getAlgorithm() == null ? "HS256" : props.getAlgorithm().toUpperCase();
        this.algorithmName = alg;
        switch (alg) {
            case "HS256" -> {
                String secret = props.getSecret();
                if (secret == null || secret.isBlank()) {
                    log.warn("iot.security.jwt.secret not configured — device JWT issuance will fail until set.");
                    return;
                }
                byte[] decoded;
                try {
                    decoded = Decoders.BASE64.decode(secret);
                } catch (IllegalArgumentException e) {
                    decoded = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                if (decoded.length < 32) {
                    throw new IllegalStateException(
                            "iot.security.jwt.secret must decode to >= 32 bytes for HS256, got " + decoded.length);
                }
                this.signingKey = Keys.hmacShaKeyFor(decoded);
                this.verifyKey = this.signingKey;
            }
            case "RS256" -> {
                this.signingKey = loadPrivateKey(props.getPrivateKey());
                this.verifyKey = loadPublicKey(props.getPublicKey());
            }
            default -> throw new IllegalStateException(
                    "Unsupported iot.security.jwt.algorithm: " + alg + " (expected HS256 or RS256)");
        }
    }

    /**
     * Issue a signed device JWT. Claim layout matches the EMQX 5 JWT auth
     * plugin's default {@code acl} schema.
     *
     * @return compact JWT
     */
    public String issueDeviceJwt(IotDeviceJwtClaims claims) {
        if (claims == null) {
            throw new MetaServiceException("iot.error.jwt_claims_null");
        }
        if (signingKey == null) {
            throw new MetaServiceException("iot.error.jwt_not_configured");
        }
        long now = System.currentTimeMillis();
        long expMs = now + props.getTtl().toMillis();
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("tenant_id", claims.tenantId());
        custom.put("product_key", claims.productKey());
        custom.put("device_code", claims.deviceCode());
        custom.put("iot_id", claims.iotId());
        List<Map<String, String>> aclList = new ArrayList<>();
        if (claims.acl() != null) {
            for (AclEntry e : claims.acl()) {
                if (e == null) continue;
                Map<String, String> rec = new LinkedHashMap<>();
                rec.put("action", e.action());
                rec.put("topic", e.topic());
                aclList.add(rec);
            }
        }
        custom.put("acl", aclList);

        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(claims.deviceCode())
                .issuedAt(new Date(now))
                .expiration(new Date(expMs))
                .claims(custom)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verify a device JWT and return its claims. Throws
     * {@link MetaServiceException} on any failure (expired / bad sig / malformed).
     */
    public Claims verifyDeviceJwt(String token) {
        if (token == null || token.isBlank()) {
            throw new MetaServiceException("iot.error.jwt_blank");
        }
        if (verifyKey == null) {
            throw new MetaServiceException("iot.error.jwt_not_configured");
        }
        try {
            if (verifyKey instanceof javax.crypto.SecretKey sk) {
                return Jwts.parser()
                        .verifyWith(sk)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
            }
            return Jwts.parser()
                    .verifyWith((PublicKey) verifyKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            throw new MetaServiceException("iot.error.jwt_invalid: " + e.getMessage(), e);
        }
    }

    String algorithmName() {
        return algorithmName;
    }

    private PrivateKey loadPrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            byte[] der = pemToDer(pem);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("iot.security.jwt.privateKey load failed: " + e.getMessage(), e);
        }
    }

    private PublicKey loadPublicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            byte[] der = pemToDer(pem);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("iot.security.jwt.publicKey load failed: " + e.getMessage(), e);
        }
    }

    private byte[] pemToDer(String pem) {
        String body = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(body);
    }
}
