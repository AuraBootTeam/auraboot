package com.auraboot.framework.aurabot.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed one-shot store for {@code skill.preview} → {@code skill.execute}
 * token handoff (Plan Step 5 / SPI Contract §6 §11).
 *
 * <p><strong>Lifecycle.</strong>
 * <ol>
 *     <li>{@code skill.preview} computes a non-mutating preview, then
 *         {@link #save} mints a UUID token, stores the
 *         {@code (tenantId, skillName, paramsHash, payload)} envelope under
 *         {@code aurabot:preview:{token}} with TTL 10 min, and returns the
 *         token to the caller.</li>
 *     <li>The caller passes that token into {@code skill.execute}.</li>
 *     <li>{@link #consume} performs a one-shot read-and-delete. Three
 *         mismatches all return {@link Optional#empty()} — token unknown,
 *         skill mismatch, params hash mismatch. The validator (B4) maps that
 *         empty into HTTP 422 {@code PREVIEW_TOKEN_INVALID}.</li>
 * </ol>
 *
 * <p><strong>paramsHash.</strong> SHA-256 over a canonical JSON encoding of
 * the params (Jackson with {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}).
 * This blocks token reuse against a different params payload — a subtle
 * preview-then-execute privilege-escalation vector if hashes weren't
 * verified. Canonicalization keeps the hash stable across key insertion
 * order and whitespace.
 *
 * <p><strong>Failure mode.</strong> As with {@link SkillIdempotencyStore},
 * Redis errors propagate to the caller (B4 validator). No internal retry /
 * fallback to memory — token handoff must be globally consistent and a
 * silent in-memory fallback would let a load-balanced execute land on a
 * different node and lose the token.
 */
@Slf4j
@Component
public class PreviewTokenStore {

    public static final String KEY_PREFIX = "aurabot:preview:";
    public static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;

    public PreviewTokenStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        // Independent mapper so we don't mutate the application-wide one;
        // ORDER_MAP_ENTRIES_BY_KEYS only affects POJO Map<>s, but mapper.writeValueAsString
        // on a JsonNode also respects the field-ordering serializer; we additionally
        // sort ObjectNode keys ourselves for determinism across Jackson versions.
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /** Build the Redis key for a preview token. Public for B4 / tests. */
    public static String buildKey(String token) {
        return KEY_PREFIX + token;
    }

    /**
     * Mint a new token and persist the preview envelope. Returns the token —
     * the caller embeds it in the {@code skill.preview} response.
     */
    public String save(long tenantId, String skillName, JsonNode params, JsonNode previewPayload) {
        String token = UUID.randomUUID().toString();
        String paramsHash = hashParams(params);

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("tenantId", tenantId);
        envelope.put("skillName", skillName);
        envelope.put("paramsHash", paramsHash);
        envelope.set("payload", previewPayload == null ? objectMapper.nullNode() : previewPayload);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            // ObjectNode → JSON cannot realistically fail; rethrow as
            // unchecked so the validator sees a 500 rather than us silently
            // swallowing a serialization bug.
            throw new IllegalStateException("Failed to serialize preview envelope", e);
        }

        redisTemplate.opsForValue().set(buildKey(token), json, TTL);
        log.debug("Preview token minted token={} tenant={} skill={}", token, tenantId, skillName);
        return token;
    }

    /**
     * One-shot read-and-delete. Returns the stored payload only when the
     * triple ({@code expectedSkill}, expected paramsHash, token presence) all
     * match — any mismatch returns {@link Optional#empty()}.
     *
     * <p>The Redis key is deleted unconditionally on a cache hit so that even
     * a partial-match attempt (token present but skill / params wrong) still
     * burns the token. This is intentional: a mismatched consume is most
     * likely an attack or a client bug; either way we want one-shot
     * semantics enforced.
     */
    public Optional<PreviewPayload> consume(String token, String expectedSkill, JsonNode expectedParams) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String key = buildKey(token);
        String json = redisTemplate.opsForValue().getAndDelete(key);
        if (json == null) {
            log.debug("Preview token miss token={}", token);
            return Optional.empty();
        }

        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            // Stored value is corrupt — log and treat as miss. Do not throw:
            // the token has already been deleted; surfacing a 500 here would
            // be more misleading than the 422 the validator produces on empty.
            log.warn("Preview token envelope corrupt token={} err={}", token, e.getMessage());
            return Optional.empty();
        }

        long tenantId = envelope.path("tenantId").asLong();
        String storedSkill = envelope.path("skillName").asText(null);
        String storedHash = envelope.path("paramsHash").asText(null);
        JsonNode payload = envelope.path("payload");

        if (storedSkill == null || !storedSkill.equals(expectedSkill)) {
            log.debug("Preview token skill mismatch token={} stored={} expected={}",
                    token, storedSkill, expectedSkill);
            return Optional.empty();
        }
        String expectedHash = hashParams(expectedParams);
        if (storedHash == null || !storedHash.equals(expectedHash)) {
            log.debug("Preview token paramsHash mismatch token={} skill={}", token, expectedSkill);
            return Optional.empty();
        }

        log.debug("Preview token consumed token={} tenant={} skill={}", token, tenantId, expectedSkill);
        return Optional.of(new PreviewPayload(tenantId, expectedSkill, payload));
    }

    /** SHA-256 over canonicalized params JSON. */
    String hashParams(JsonNode params) {
        JsonNode normalized = params == null ? objectMapper.nullNode() : params;
        String canonical;
        try {
            canonical = canonicalMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to canonicalize params for hash", e);
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE — propagate as unchecked.
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    /** Decoded preview envelope returned by {@link #consume}. */
    public record PreviewPayload(long tenantId, String skillName, JsonNode payload) {
    }
}
