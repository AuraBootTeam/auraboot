package com.auraboot.framework.aurabot.skill;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Redis IT for {@link SkillIdempotencyStore} (Plan Step 5 / SPI §6).
 *
 * <p>Targets the isolated {@code auraboot-skills-c2} stack on host port
 * 26389. Uses a per-test {@code skillName} so parallel suites and reruns do
 * not collide; an {@link AfterEach} also wipes any
 * {@code aurabot:idemp:*} keys this method may have left behind so B4+
 * tests start clean.
 */
@ActiveProfiles({"integration-test", "skills-c2-test"})
@DisplayName("SkillIdempotencyStore — Redis ledger (real Redis on :26389)")
class SkillIdempotencyStoreIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SkillIdempotencyStore store;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanupIdempotencyKeys() {
        Set<String> keys = redisTemplate.keys(SkillIdempotencyStore.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private String freshSkill() {
        return "it-aurabot-idemp-" + UniqueIdGenerator.generate().toLowerCase();
    }

    private String freshIdem() {
        return "it-aurabot-idem-" + UniqueIdGenerator.generate();
    }

    private String freshPid() {
        return "usr_" + UniqueIdGenerator.generate();
    }

    @Test
    @DisplayName("tryClaim — first call wins (empty), second returns prior pid")
    void tryClaim_firstCallSucceeds_secondReturnsPrior() {
        long tenant = 4242L;
        String skill = freshSkill();
        String idem = freshIdem();
        String firstPid = freshPid();
        String secondPid = freshPid();

        Optional<String> firstClaim = store.tryClaim(tenant, skill, idem, firstPid);
        assertThat(firstClaim).as("first claim must succeed (no prior)").isEmpty();

        Optional<String> secondClaim = store.tryClaim(tenant, skill, idem, secondPid);
        assertThat(secondClaim)
                .as("second claim must observe the first pid")
                .isPresent()
                .hasValue(firstPid);

        // Underlying Redis key still carries the first pid (SETNX semantics).
        String stored = redisTemplate.opsForValue().get(
                SkillIdempotencyStore.buildKey(tenant, skill, idem));
        assertThat(stored).isEqualTo(firstPid);
    }

    @Test
    @DisplayName("tryClaim — after TTL elapses a fresh claim is allowed")
    void tryClaim_afterTtl_allowsNewClaim() throws InterruptedException {
        long tenant = 4242L;
        String skill = freshSkill();
        String idem = freshIdem();
        String firstPid = freshPid();
        String secondPid = freshPid();

        // Plant the claim with a 1s TTL so the test can wait it out without
        // monkey-patching production constants. The key uses the same format
        // builder so the production path can read / overwrite it.
        String key = SkillIdempotencyStore.buildKey(tenant, skill, idem);
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, firstPid, Duration.ofSeconds(1));
        assertThat(ok).isTrue();

        // While the claim is live, a duplicate sees the prior pid.
        Optional<String> midClaim = store.tryClaim(tenant, skill, idem, secondPid);
        assertThat(midClaim).hasValue(firstPid);

        // Wait past TTL.
        Thread.sleep(1500);

        // A fresh claim now succeeds and the new pid takes over.
        Optional<String> postTtl = store.tryClaim(tenant, skill, idem, secondPid);
        assertThat(postTtl).as("post-TTL claim must succeed").isEmpty();
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(secondPid);
    }

    @Test
    @DisplayName("release — clears claim so the same key can be reclaimed")
    void release_clearsClaim() {
        long tenant = 4242L;
        String skill = freshSkill();
        String idem = freshIdem();
        String firstPid = freshPid();
        String thirdPid = freshPid();

        Optional<String> first = store.tryClaim(tenant, skill, idem, firstPid);
        assertThat(first).isEmpty();

        store.release(tenant, skill, idem);

        // After release the key is gone, so a new tryClaim wins outright.
        Optional<String> reclaim = store.tryClaim(tenant, skill, idem, thirdPid);
        assertThat(reclaim).as("release must allow a new winner").isEmpty();
        assertThat(redisTemplate.opsForValue().get(
                SkillIdempotencyStore.buildKey(tenant, skill, idem))).isEqualTo(thirdPid);

        // release on a missing key is a no-op (no exception).
        store.release(tenant, skill, "no-such-idem-key");
    }
}
