package com.auraboot.framework.iot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for {@link IotJwtService}. Defaults target the EMQX 5 JWT
 * authentication plugin: HS256 with a shared secret and an {@code acl} claim
 * array of {@code {action, topic}} entries.
 *
 * @since 2.6.0
 */
@ConfigurationProperties("iot.security.jwt")
public class IotJwtProperties {

    /** {@code HS256} (HMAC-SHA-256) or {@code RS256} (RSA-SHA-256). */
    private String algorithm = "HS256";

    /** Base64-encoded shared secret for HS256. Must decode to ≥ 32 bytes. */
    private String secret = "";

    /** PEM-encoded RSA private key (PKCS#8) for RS256 signing. */
    private String privateKey = "";

    /** PEM-encoded RSA public key (X.509 SubjectPublicKeyInfo) for RS256 verify. */
    private String publicKey = "";

    /** Token expiry; defaults to 7 days. */
    private Duration ttl = Duration.ofDays(7);

    /** {@code iss} claim. */
    private String issuer = "auraboot-iot";

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
}
