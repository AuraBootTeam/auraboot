# Site-Key Anonymous Ingestion (SP2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open a public, unauthenticated `POST /api/collect/keyed` that resolves an anonymous visitor's `site_key` to its owning tenant, applies an abuse-protection baseline, and ingests the events into the existing behavior store — plus the global `UNIQUE(site_key)` DB index the hot path needs.

**Architecture:** A separate whitelisted controller (the existing `/api/collect` stays authenticated, untouched). The controller delegates to a `KeyedCollectGuard` that runs an ordered protection chain (key resolve → key status → origin allowlist → per-key/per-IP rate limit → payload caps), then to a new `BehaviorCollectService.recordAnonymous(events, tenantId)` that reuses the existing entity mapping/idempotency with the tenant from the key and `userId=null`. The `site_key` DB index is converged idempotently via a dual-trigger `SiteKeyIndexInitializer` (Option A) reusing the platform's `SchemaManagementService.createFieldIndex`.

**Tech Stack:** Java 17 / Spring Boot / MyBatis-Plus / PostgreSQL / JUnit5 + Mockito + AssertJ / Spring MockMvc. Host-first, zero docker.

## Global Constraints

- **Repo / worktree:** `/Users/ghj/work/auraboot/auraboot-sitekey-sp2`, branch `feat/site-key-anonymous-ingestion-sp2` (off origin/main). All edits here, never canonical `main`.
- **No self-heal (§8):** unknown/disabled key, origin mismatch, over-limit → reject with a field-level reason code; never create a default key, never fall back, never silently allow.
- **Separate anonymous entrypoint (AGENTS):** keyed path must NOT reuse JWT auth; authenticated `/api/collect` stays `authenticated()` and unchanged (regression-asserted).
- **Index semantic:** global `UNIQUE(site_key)` single-column (NOT `(tenant_id, site_key)`) — resolve is cross-tenant. See `docs/backlog/2026-06-21-mt-dynamic-table-index-creation-analysis.md`.
- **i18n (§3):** no hardcoded Chinese in code; reason codes are stable English tokens.
- **Tests are the gate (§1):** every unit + the real-PG IT green before done; host-first zero docker.
- **Test command (unit, JVM):** `./gradlew :platform:test --tests "<FQCN>"` (multi-module needs `:platform:test`).
- **ITs need a real PG** on the `integration-test` profile (shared host `aura_boot`); if `relation does not exist` flakes, re-apply migration first (env-invalid, not code).

---

## File Structure

**Create:**
- `platform/src/main/java/com/auraboot/framework/behavior/keyed/SiteKeyOriginPolicy.java` — origin allowlist enforcement (pure matcher + cached DB load)
- `platform/src/main/java/com/auraboot/framework/behavior/keyed/KeyedCollectGuard.java` — ordered protection chain, returns resolved tenantId
- `platform/src/main/java/com/auraboot/framework/behavior/keyed/KeyedCollectController.java` — `POST /api/collect/keyed`
- `platform/src/main/java/com/auraboot/framework/behavior/sitekey/SiteKeyIndexInitializer.java` — Option A dual-trigger index convergence
- `platform/src/test/java/com/auraboot/framework/behavior/keyed/SiteKeyOriginPolicyTest.java`
- `platform/src/test/java/com/auraboot/framework/behavior/keyed/KeyedCollectGuardTest.java`
- `platform/src/test/java/com/auraboot/framework/behavior/service/BehaviorCollectServiceAnonymousTest.java`
- `platform/src/test/java/com/auraboot/framework/behavior/sitekey/SiteKeyIndexInitializerTest.java`
- `platform/src/test/java/com/auraboot/framework/behavior/keyed/KeyedCollectIT.java` — real-PG HTTP golden

**Modify:**
- `platform/src/main/java/com/auraboot/framework/behavior/service/BehaviorCollectService.java` — extract shared batch method, add `recordAnonymous`
- `platform/src/main/java/com/auraboot/framework/application/security/WhiteList.java` — whitelist `/api/collect/keyed`
- `docs/superpowers/specs/2026-06-21-site-key-registry-design.md` (§9.1) + `docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md` + `docs/handover/HANDOVER-2026-06-21-site-key-registry-sp1.md` — correct index wording

---

## Task 1: `recordAnonymous` ingestion core

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/behavior/service/BehaviorCollectService.java`
- Test: `platform/src/test/java/com/auraboot/framework/behavior/service/BehaviorCollectServiceAnonymousTest.java`

**Interfaces:**
- Consumes: existing `BehaviorEventMapper.insert`, `BehaviorEventInput`, `toEntity`.
- Produces: `int recordAnonymous(List<BehaviorEventInput> events, long tenantId)` — persists with the given tenant, `userId=null`, client `anonId`; returns accepted count (duplicates count as accepted). Existing `int record(List<BehaviorEventInput>)` unchanged in behavior.

- [ ] **Step 1: Write the failing test**

```java
package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BehaviorCollectServiceAnonymousTest {

    private final BehaviorEventMapper mapper = mock(BehaviorEventMapper.class);
    private final BehaviorCollectService service =
            new BehaviorCollectService(mapper, new ObjectMapper());

    private BehaviorEventInput event(String id) {
        BehaviorEventInput in = new BehaviorEventInput();
        in.setEventId(id);
        in.setEventName("page_view");
        in.setAnonId("anon-123");
        return in;
    }

    @Test
    void recordAnonymous_setsTenantFromArg_userNull_anonIdPassedThrough() {
        int accepted = service.recordAnonymous(List.of(event("e1")), 7001L);

        assertThat(accepted).isEqualTo(1);
        ArgumentCaptor<BehaviorEvent> cap = ArgumentCaptor.forClass(BehaviorEvent.class);
        verify(mapper).insert(cap.capture());
        BehaviorEvent e = cap.getValue();
        assertThat(e.getTenantId()).isEqualTo(7001L);
        assertThat(e.getUserId()).isNull();
        assertThat(e.getAnonId()).isEqualTo("anon-123");
    }

    @Test
    void recordAnonymous_emptyOrNull_returnsZero_noInsert() {
        assertThat(service.recordAnonymous(List.of(), 7001L)).isZero();
        assertThat(service.recordAnonymous(null, 7001L)).isZero();
        verify(mapper, never()).insert(any());
    }

    @Test
    void recordAnonymous_skipsMalformed_missingEventIdOrName() {
        BehaviorEventInput bad = new BehaviorEventInput(); // no id/name
        assertThat(service.recordAnonymous(List.of(bad), 7001L)).isZero();
        verify(mapper, never()).insert(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :platform:test --tests "com.auraboot.framework.behavior.service.BehaviorCollectServiceAnonymousTest"`
Expected: FAIL — `recordAnonymous` does not exist (compile error).

- [ ] **Step 3: Refactor `record` to share a batch method and add `recordAnonymous`**

In `BehaviorCollectService.java`, replace the body of `record(...)` and add the new methods (keep `toEntity`/`writeProps` as-is, but change `toEntity` signature to accept tenant+user — it already does):

```java
/** Authenticated path: tenant/user from the auth context (unchanged behavior). */
public int record(List<BehaviorEventInput> events) {
    Long tenantId = MetaContext.getCurrentTenantId();
    if (tenantId == null) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenant_required");
    }
    return recordBatch(events, tenantId, MetaContext.getCurrentUserId());
}

/**
 * Anonymous/keyed path (SP2): tenant resolved from the public site key by the caller,
 * no user. The client-supplied anonId is the only identity.
 */
public int recordAnonymous(List<BehaviorEventInput> events, long tenantId) {
    return recordBatch(events, tenantId, null);
}

private int recordBatch(List<BehaviorEventInput> events, Long tenantId, Long userId) {
    if (events == null || events.isEmpty()) {
        return 0;
    }
    int accepted = 0;
    for (BehaviorEventInput in : events) {
        if (in == null || in.getEventId() == null || in.getEventName() == null) {
            continue; // skip malformed; per-event, not batch-fatal
        }
        try {
            behaviorEventMapper.insert(toEntity(in, tenantId, userId));
            accepted++;
        } catch (DuplicateKeyException dup) {
            accepted++; // idempotent retry — already stored
        }
    }
    return accepted;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :platform:test --tests "com.auraboot.framework.behavior.service.BehaviorCollectServiceAnonymousTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/behavior/service/BehaviorCollectService.java \
        platform/src/test/java/com/auraboot/framework/behavior/service/BehaviorCollectServiceAnonymousTest.java
git commit -m "feat(behavior): recordAnonymous — keyed-path ingestion with tenant from site key"
```

---

## Task 2: `SiteKeyOriginPolicy` — origin allowlist enforcement

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/behavior/keyed/SiteKeyOriginPolicy.java`
- Test: `platform/src/test/java/com/auraboot/framework/behavior/keyed/SiteKeyOriginPolicyTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate` (reads `mt_behavior_site_key.origin_allowlist`).
- Produces:
  - `static boolean originMatches(String origin, List<String> allowlist)` — pure: empty/null allowlist → `true` (not configured = open); otherwise exact-host match of the request origin against any allowed entry.
  - `boolean isOriginAllowed(String siteKey, String origin)` — loads the key's allowlist (cached) and applies `originMatches`.

- [ ] **Step 1: Write the failing test (pure matcher first — DB load proven in IT)**

```java
package com.auraboot.framework.behavior.keyed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SiteKeyOriginPolicyTest {

    @Test
    void emptyOrNullAllowlist_meansOpen() {
        assertThat(SiteKeyOriginPolicy.originMatches("https://shop.acme.com", List.of())).isTrue();
        assertThat(SiteKeyOriginPolicy.originMatches("https://shop.acme.com", null)).isTrue();
    }

    @Test
    void matchesWhenOriginInAllowlist() {
        List<String> allow = List.of("https://shop.acme.com", "https://www.acme.com");
        assertThat(SiteKeyOriginPolicy.originMatches("https://shop.acme.com", allow)).isTrue();
    }

    @Test
    void rejectsWhenOriginNotInAllowlist() {
        List<String> allow = List.of("https://shop.acme.com");
        assertThat(SiteKeyOriginPolicy.originMatches("https://evil.example", allow)).isFalse();
    }

    @Test
    void rejectsNullOriginWhenAllowlistConfigured() {
        assertThat(SiteKeyOriginPolicy.originMatches(null, List.of("https://shop.acme.com"))).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :platform:test --tests "com.auraboot.framework.behavior.keyed.SiteKeyOriginPolicyTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `SiteKeyOriginPolicy`**

```java
package com.auraboot.framework.behavior.keyed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Enforces a site key's {@code origin_allowlist} for the public keyed-collect path.
 * SP1 stored the allowlist (store-only); SP2 enforces it. An empty/unset allowlist
 * means "not configured" → open (recorded by the caller), matching GA-style public
 * collection where most keys do not pin origins.
 */
@Slf4j
@Service
public class SiteKeyOriginPolicy {

    private static final String TABLE = "mt_behavior_site_key";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** Cache of active site_key -> allowlist (parsed). Mirrors the registry hot path. */
    private final Cache<String, List<String>> allowlistByKey = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public SiteKeyOriginPolicy(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Pure: empty/null allowlist = open; otherwise the origin must be an exact allowlist entry. */
    public static boolean originMatches(String origin, List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            return true;
        }
        return origin != null && allowlist.contains(origin);
    }

    public boolean isOriginAllowed(String siteKey, String origin) {
        return originMatches(origin, loadAllowlist(siteKey));
    }

    private List<String> loadAllowlist(String siteKey) {
        List<String> cached = allowlistByKey.getIfPresent(siteKey);
        if (cached != null) {
            return cached;
        }
        List<String> allow = List.of();
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT origin_allowlist FROM " + TABLE + " WHERE site_key = ? AND status = 'active' LIMIT 1",
                    String.class, siteKey);
            if (json != null && !json.isBlank()) {
                allow = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            }
        } catch (EmptyResultDataAccessException ignored) {
            // unknown/disabled key — origin check is moot (registry rejects first); keep open list
        } catch (Exception e) {
            log.warn("Failed to parse origin_allowlist for a site key: {}", e.getMessage());
        }
        allowlistByKey.put(siteKey, allow);
        return allow;
    }
}
```

> **Build note (jsonb gotcha):** `origin_allowlist` is jsonb. Reading it as `String` via JdbcTemplate returns the JSON text on Postgres; if a `PGobject` surfaces, switch to `JsonbColumns.toJsonText`. Run `scripts/check-jsonb-typehandler.sh`. The IT (Task 6) proves the real read.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :platform:test --tests "com.auraboot.framework.behavior.keyed.SiteKeyOriginPolicyTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/behavior/keyed/SiteKeyOriginPolicy.java \
        platform/src/test/java/com/auraboot/framework/behavior/keyed/SiteKeyOriginPolicyTest.java
git commit -m "feat(behavior): SiteKeyOriginPolicy — enforce per-key origin allowlist (empty=open)"
```

---

## Task 3: `KeyedCollectGuard` — ordered protection chain

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/behavior/keyed/KeyedCollectGuard.java`
- Test: `platform/src/test/java/com/auraboot/framework/behavior/keyed/KeyedCollectGuardTest.java`

**Interfaces:**
- Consumes: `SiteKeyRegistry.resolveTenant(String)→Optional<Long>`, `SiteKeyOriginPolicy.isOriginAllowed(String,String)→boolean`, `ApiRateLimiter.isAllowed(String,int)→boolean`.
- Produces: `long check(String siteKey, String origin, String clientIp, List<BehaviorEventInput> events)` — returns the resolved tenantId, or throws `ResponseStatusException` with status + reason: 403 `site_key_invalid` / 403 `origin_not_allowed` / 429 `rate_limited` / 400 `batch_too_large`. Thresholds injected via `@Value` (defaults: per-key 600/min, per-IP 300/min, batch 50).

- [ ] **Step 1: Write the failing test**

```java
package com.auraboot.framework.behavior.keyed;

import com.auraboot.framework.auth.service.ApiRateLimiter;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.sitekey.SiteKeyRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KeyedCollectGuardTest {

    private final SiteKeyRegistry registry = mock(SiteKeyRegistry.class);
    private final SiteKeyOriginPolicy origin = mock(SiteKeyOriginPolicy.class);
    private final ApiRateLimiter rateLimiter = mock(ApiRateLimiter.class);
    private final KeyedCollectGuard guard =
            new KeyedCollectGuard(registry, origin, rateLimiter, 600, 300, 50);

    private List<BehaviorEventInput> oneEvent() {
        BehaviorEventInput in = new BehaviorEventInput();
        in.setEventId("e1");
        in.setEventName("page_view");
        return List.of(in);
    }

    @Test
    void happyPath_returnsResolvedTenant() {
        when(registry.resolveTenant("abk_x")).thenReturn(Optional.of(42L));
        when(origin.isOriginAllowed("abk_x", "https://a.com")).thenReturn(true);
        when(rateLimiter.isAllowed(anyString(), anyInt())).thenReturn(true);

        assertThat(guard.check("abk_x", "https://a.com", "1.2.3.4", oneEvent())).isEqualTo(42L);
    }

    @Test
    void unknownKey_403_siteKeyInvalid() {
        when(registry.resolveTenant("abk_x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.check("abk_x", "https://a.com", "1.2.3.4", oneEvent()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("site_key_invalid");
        verifyNoInteractions(origin, rateLimiter);
    }

    @Test
    void blankKey_403() {
        assertThatThrownBy(() -> guard.check("  ", "https://a.com", "1.2.3.4", oneEvent()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("site_key_invalid");
    }

    @Test
    void originNotAllowed_403() {
        when(registry.resolveTenant("abk_x")).thenReturn(Optional.of(42L));
        when(origin.isOriginAllowed("abk_x", "https://evil.com")).thenReturn(false);
        assertThatThrownBy(() -> guard.check("abk_x", "https://evil.com", "1.2.3.4", oneEvent()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("origin_not_allowed");
    }

    @Test
    void perKeyRateExceeded_429() {
        when(registry.resolveTenant("abk_x")).thenReturn(Optional.of(42L));
        when(origin.isOriginAllowed(anyString(), anyString())).thenReturn(true);
        when(rateLimiter.isAllowed("collect:key:abk_x", 600)).thenReturn(false);
        assertThatThrownBy(() -> guard.check("abk_x", "https://a.com", "1.2.3.4", oneEvent()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rate_limited");
    }

    @Test
    void batchTooLarge_400() {
        when(registry.resolveTenant("abk_x")).thenReturn(Optional.of(42L));
        when(origin.isOriginAllowed(anyString(), anyString())).thenReturn(true);
        when(rateLimiter.isAllowed(anyString(), anyInt())).thenReturn(true);
        BehaviorEventInput in = new BehaviorEventInput();
        in.setEventId("e"); in.setEventName("x");
        List<BehaviorEventInput> tooMany = java.util.Collections.nCopies(51, in);
        assertThatThrownBy(() -> guard.check("abk_x", "https://a.com", "1.2.3.4", tooMany))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("batch_too_large");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :platform:test --tests "com.auraboot.framework.behavior.keyed.KeyedCollectGuardTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `KeyedCollectGuard`**

```java
package com.auraboot.framework.behavior.keyed;

import com.auraboot.framework.auth.service.ApiRateLimiter;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.sitekey.SiteKeyRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Ordered abuse-protection chain for the public keyed-collect endpoint. Runs cheapest-first:
 * key resolve → origin allowlist → per-key/per-IP rate limit → batch cap. Any failure throws
 * a {@link ResponseStatusException} with a stable reason code. No self-heal: a failed check
 * rejects, it never creates/relaxes anything.
 */
@Component
public class KeyedCollectGuard {

    private final SiteKeyRegistry registry;
    private final SiteKeyOriginPolicy originPolicy;
    private final ApiRateLimiter rateLimiter;
    private final int maxPerKey;
    private final int maxPerIp;
    private final int maxBatch;

    public KeyedCollectGuard(SiteKeyRegistry registry,
                             SiteKeyOriginPolicy originPolicy,
                             ApiRateLimiter rateLimiter,
                             @Value("${behavior.collect.keyed.max-per-key-per-min:600}") int maxPerKey,
                             @Value("${behavior.collect.keyed.max-per-ip-per-min:300}") int maxPerIp,
                             @Value("${behavior.collect.keyed.max-batch:50}") int maxBatch) {
        this.registry = registry;
        this.originPolicy = originPolicy;
        this.rateLimiter = rateLimiter;
        this.maxPerKey = maxPerKey;
        this.maxPerIp = maxPerIp;
        this.maxBatch = maxBatch;
    }

    /** @return the tenant the key resolves to; throws with a reason code if any check fails. */
    public long check(String siteKey, String origin, String clientIp, List<BehaviorEventInput> events) {
        if (siteKey == null || siteKey.isBlank()) {
            throw reject(HttpStatus.FORBIDDEN, "site_key_invalid");
        }
        Long tenantId = registry.resolveTenant(siteKey).orElse(null);
        if (tenantId == null) {
            throw reject(HttpStatus.FORBIDDEN, "site_key_invalid");
        }
        if (!originPolicy.isOriginAllowed(siteKey, origin)) {
            throw reject(HttpStatus.FORBIDDEN, "origin_not_allowed");
        }
        if (!rateLimiter.isAllowed("collect:key:" + siteKey, maxPerKey)) {
            throw reject(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        if (clientIp != null && !rateLimiter.isAllowed("collect:ip:" + clientIp, maxPerIp)) {
            throw reject(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        if (events != null && events.size() > maxBatch) {
            throw reject(HttpStatus.BAD_REQUEST, "batch_too_large");
        }
        return tenantId;
    }

    private ResponseStatusException reject(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :platform:test --tests "com.auraboot.framework.behavior.keyed.KeyedCollectGuardTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/behavior/keyed/KeyedCollectGuard.java \
        platform/src/test/java/com/auraboot/framework/behavior/keyed/KeyedCollectGuardTest.java
git commit -m "feat(behavior): KeyedCollectGuard — ordered abuse-protection chain for keyed collect"
```

---

## Task 4: `KeyedCollectController` + whitelist opening

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/behavior/keyed/KeyedCollectController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/application/security/WhiteList.java`
- Test: covered by the real-PG IT (Task 6) — reachability + ingestion need the full filter chain + DB.

**Interfaces:**
- Consumes: `KeyedCollectGuard.check(...)`, `BehaviorCollectService.recordAnonymous(events, tenantId)`, `CollectRequest.getEvents()`.
- Produces: `POST /api/collect/keyed`, header `X-Site-Key`, body `CollectRequest`, response `{"accepted": n}`.

- [ ] **Step 1: Implement the controller**

```java
package com.auraboot.framework.behavior.keyed;

import com.auraboot.framework.behavior.dto.CollectRequest;
import com.auraboot.framework.behavior.service.BehaviorCollectService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public, unauthenticated anonymous ingestion (SP2). A published app embeds a public
 * {@code abk_} site key; the visitor's browser posts events here with no JWT. The server —
 * never the client — resolves the owning tenant from the key, runs the abuse-protection
 * guard, then ingests as anonymous. The authenticated {@code /api/collect} is unchanged.
 */
@RestController
@RequestMapping("/api/collect/keyed")
@RequiredArgsConstructor
public class KeyedCollectController {

    private final KeyedCollectGuard guard;
    private final BehaviorCollectService behaviorCollectService;

    @PostMapping
    public Map<String, Object> collect(@RequestHeader(value = "X-Site-Key", required = false) String siteKey,
                                       @RequestBody CollectRequest request,
                                       HttpServletRequest http) {
        String origin = originOf(http);
        String clientIp = clientIpOf(http);
        long tenantId = guard.check(siteKey, origin, clientIp, request.getEvents());
        int accepted = behaviorCollectService.recordAnonymous(request.getEvents(), tenantId);
        return Map.of("accepted", accepted);
    }

    private static String originOf(HttpServletRequest http) {
        String origin = http.getHeader("Origin");
        return origin != null ? origin : http.getHeader("Referer");
    }

    private static String clientIpOf(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return http.getRemoteAddr();
    }
}
```

- [ ] **Step 2: Whitelist the endpoint**

In `WhiteList.java`, add to the `whiteList` array (near the other `/api/...` public entries):

```java
            // Anonymous behavior ingestion (SP2): keyed by a public site_key, tenant resolved
            // server-side; the authenticated /api/collect stays JWT-only. Protected by
            // KeyedCollectGuard (origin allowlist + rate limit + caps), not by auth.
            "/api/collect/keyed",
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :platform:compileJava`
Expected: BUILD SUCCESSFUL. (Functional reachability/ingestion is asserted by Task 6's IT through the real filter chain + DB.)

- [ ] **Step 4: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/behavior/keyed/KeyedCollectController.java \
        platform/src/main/java/com/auraboot/framework/application/security/WhiteList.java
git commit -m "feat(behavior): POST /api/collect/keyed public endpoint + whitelist"
```

---

## Task 5: `SiteKeyIndexInitializer` — Option A dual-trigger index convergence

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/behavior/sitekey/SiteKeyIndexInitializer.java`
- Test: `platform/src/test/java/com/auraboot/framework/behavior/sitekey/SiteKeyIndexInitializerTest.java`

**Interfaces:**
- Consumes: `SchemaManagementService.createFieldIndex(String modelCode, String fieldCode, IndexType)`, `PluginImportCompletedEvent.getPluginCode()`, `JdbcTemplate` (for `to_regclass` existence check).
- Produces: idempotent `void ensureIndex()` invoked on `PluginImportCompletedEvent` (plugin `behavior`) and on `ApplicationReadyEvent` (only if table exists).

- [ ] **Step 1: Write the failing test**

```java
package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.meta.dto.IndexType;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.*;

class SiteKeyIndexInitializerTest {

    private final SchemaManagementService schema = mock(SchemaManagementService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SiteKeyIndexInitializer init = new SiteKeyIndexInitializer(schema, jdbc);

    @Test
    void importOfBehaviorPlugin_createsIndex() {
        init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "behavior"));
        verify(schema).createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE);
    }

    @Test
    void importOfOtherPlugin_noop() {
        init.onPluginImportCompleted(new PluginImportCompletedEvent(this, 1L, "crm"));
        verifyNoInteractions(schema);
    }

    @Test
    void appReady_whenTableExists_createsIndex() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(String.class)))
                .thenReturn("mt_behavior_site_key");
        init.onApplicationReady();
        verify(schema).createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE);
    }

    @Test
    void appReady_whenTableMissing_noop() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(String.class))).thenReturn(null);
        init.onApplicationReady();
        verifyNoInteractions(schema);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :platform:test --tests "com.auraboot.framework.behavior.sitekey.SiteKeyIndexInitializerTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `SiteKeyIndexInitializer`**

```java
package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.meta.dto.IndexType;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Converges the global {@code UNIQUE(site_key)} index on the dynamic {@code mt_behavior_site_key}
 * table (Option A). Config-level unique/searchable is inert on {@code mt_} tables and Flyway can't
 * reach a table the plugin import creates at runtime, so this reuses the platform's own idempotent
 * {@link SchemaManagementService#createFieldIndex} — column-level, global, with built-in
 * {@code indexExists} short-circuit. Dual trigger: fire on the {@code behavior} plugin import
 * (table just (re)created), and a one-time backstop on app-ready for deployments where the table
 * predates this code. Both call the same idempotent path. See
 * docs/backlog/2026-06-21-mt-dynamic-table-index-creation-analysis.md.
 */
@Slf4j
@Component
public class SiteKeyIndexInitializer {

    private static final String PLUGIN = "behavior";
    private static final String MODEL = "behavior_site_key";
    private static final String FIELD = "site_key";
    private static final String TABLE = "mt_behavior_site_key";

    private final SchemaManagementService schemaManagementService;
    private final JdbcTemplate jdbcTemplate;

    public SiteKeyIndexInitializer(SchemaManagementService schemaManagementService,
                                   JdbcTemplate jdbcTemplate) {
        this.schemaManagementService = schemaManagementService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener
    public void onPluginImportCompleted(PluginImportCompletedEvent event) {
        if (PLUGIN.equals(event.getPluginCode())) {
            ensureIndex();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (tableExists()) {
            ensureIndex();
        }
    }

    private void ensureIndex() {
        try {
            schemaManagementService.createFieldIndex(MODEL, FIELD, IndexType.UNIQUE);
        } catch (RuntimeException e) {
            // Index convergence must not break startup/import; createFieldIndex is idempotent,
            // so this only logs a genuine DDL failure for ops to see — it does not retry/self-heal.
            log.warn("site_key unique index convergence failed: {}", e.getMessage());
        }
    }

    private boolean tableExists() {
        String reg = jdbcTemplate.queryForObject("SELECT to_regclass('" + TABLE + "')", String.class);
        return reg != null;
    }
}
```

> **Why `@EventListener(ApplicationReadyEvent.class)` (typed, not bare):** a no-arg `@EventListener` matches every event — pinning the type makes it fire only on app-ready.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :platform:test --tests "com.auraboot.framework.behavior.sitekey.SiteKeyIndexInitializerTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/behavior/sitekey/SiteKeyIndexInitializer.java \
        platform/src/test/java/com/auraboot/framework/behavior/sitekey/SiteKeyIndexInitializerTest.java
git commit -m "feat(behavior): SiteKeyIndexInitializer — idempotent global UNIQUE(site_key) convergence"
```

---

## Task 6: Real-PG HTTP golden IT

**Files:**
- Create: `platform/src/test/java/com/auraboot/framework/behavior/keyed/KeyedCollectIT.java`

**Interfaces:**
- Consumes: the full app on `integration-test` profile via MockMvc (real security filter chain), `JdbcTemplate`, `SiteKeyIndexInitializer`, `BehaviorEventMapper`/raw SQL to read back `ab_behavior_event`.

**Mirror** `SiteKeyRegistryIT`'s fixture: `CREATE TABLE IF NOT EXISTS mt_behavior_site_key (...)`, a dedicated tenant range, `abk_it_` key prefix, cleanup before+after, never drop the table.

- [ ] **Step 1: Write the IT (all assertions)**

```java
package com.auraboot.framework.behavior.keyed;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.behavior.sitekey.SiteKeyIndexInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeyedCollectIT {

    private static final long TENANT_A = 990_201L;
    private static final long TENANT_B = 990_202L;
    private static final String KEY_A = "abk_it_keyedA000000000000000000000";
    private static final String KEY_B = "abk_it_keyedB000000000000000000000";
    private static final String KEY_DISABLED = "abk_it_keyedD000000000000000000000";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SiteKeyIndexInitializer indexInitializer;

    @BeforeAll
    void seed() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS mt_behavior_site_key (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                pid VARCHAR(32) NOT NULL,
                tenant_id BIGINT,
                site_key VARCHAR(64),
                name VARCHAR(200) NOT NULL,
                status VARCHAR(32) DEFAULT 'active',
                origin_allowlist JSONB,
                created_at TIMESTAMP DEFAULT now()
            )""");
        cleanup();
        insertKey("p_a", TENANT_A, KEY_A, "active", null);
        insertKey("p_b", TENANT_B, KEY_B, "active", null);
        insertKey("p_d", TENANT_A, KEY_DISABLED, "disabled", null);
        // Converge the index (Option A would do this on import; call directly in IT).
        indexInitializer.onApplicationReady();
    }

    @AfterAll
    void tearDown() { cleanup(); }

    private void insertKey(String pid, long tenant, String key, String status, String allowlistJson) {
        jdbc.update("INSERT INTO mt_behavior_site_key (pid, tenant_id, site_key, name, status, origin_allowlist) "
                + "VALUES (?,?,?,?,?, ?::jsonb)", pid, tenant, key, "IT " + key, status, allowlistJson);
    }

    private void cleanup() {
        jdbc.update("DELETE FROM mt_behavior_site_key WHERE site_key LIKE 'abk_it_%'");
        jdbc.update("DELETE FROM ab_behavior_event WHERE tenant_id IN (?,?)", TENANT_A, TENANT_B);
    }

    private String body(String eventId) {
        return "{\"events\":[{\"eventId\":\"" + eventId + "\",\"eventName\":\"page_view\",\"anonId\":\"anon-it\"}]}";
    }

    private int eventCount(long tenant) {
        Integer n = jdbc.queryForObject("SELECT count(1) FROM ab_behavior_event WHERE tenant_id = ?",
                Integer.class, tenant);
        return n == null ? 0 : n;
    }

    @Test @DisplayName("valid key → event lands in the key's tenant, user null, anon_id passed through")
    void validKey_ingestsToKeyTenant() throws Exception {
        mockMvc.perform(post("/api/collect/keyed").header("X-Site-Key", KEY_A)
                        .contentType("application/json").content(body("e-valid-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
        assertThat(eventCount(TENANT_A)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM ab_behavior_event WHERE tenant_id=? AND event_id='e-valid-1'",
                Long.class, TENANT_A)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT anon_id FROM ab_behavior_event WHERE tenant_id=? AND event_id='e-valid-1'",
                String.class, TENANT_A)).isEqualTo("anon-it");
    }

    @Test @DisplayName("cross-tenant isolation: key A never lands in tenant B")
    void crossTenantIsolation() throws Exception {
        mockMvc.perform(post("/api/collect/keyed").header("X-Site-Key", KEY_A)
                .contentType("application/json").content(body("e-iso-1"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT count(1) FROM ab_behavior_event WHERE tenant_id=? AND event_id='e-iso-1'",
                Integer.class, TENANT_B)).isZero();
    }

    @Test @DisplayName("unknown key → 403, nothing written")
    void unknownKey_403() throws Exception {
        int before = eventCount(TENANT_A);
        mockMvc.perform(post("/api/collect/keyed").header("X-Site-Key", "abk_it_nope000000000000000000000000")
                        .contentType("application/json").content(body("e-unknown")))
                .andExpect(status().isForbidden());
        assertThat(eventCount(TENANT_A)).isEqualTo(before);
    }

    @Test @DisplayName("disabled key → 403")
    void disabledKey_403() throws Exception {
        mockMvc.perform(post("/api/collect/keyed").header("X-Site-Key", KEY_DISABLED)
                        .contentType("application/json").content(body("e-disabled")))
                .andExpect(status().isForbidden());
    }

    @Test @DisplayName("missing site key header → 403")
    void missingKey_403() throws Exception {
        mockMvc.perform(post("/api/collect/keyed")
                        .contentType("application/json").content(body("e-nokey")))
                .andExpect(status().isForbidden());
    }

    @Test @DisplayName("index: global UNIQUE(site_key), single column, no tenant prefix")
    void indexIsGlobalUniqueSingleColumn() {
        String def = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename='mt_behavior_site_key' "
                + "AND indexdef ILIKE '%UNIQUE%(site_key)%'", String.class);
        assertThat(def).isNotNull().containsIgnoringCase("unique").contains("(site_key)");
        assertThat(def).doesNotContain("tenant_id");
    }

    @Test @DisplayName("global uniqueness enforced: same site_key in two tenants rejected")
    void globalUniquenessEnforced() {
        assertThat(catchThrowable(() ->
                insertKey("p_dup", TENANT_B, KEY_A, "active", null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test @DisplayName("resolve uses an index scan, not a seq scan")
    void resolveUsesIndexScan() {
        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT tenant_id FROM mt_behavior_site_key WHERE site_key='" + KEY_A
                + "' AND status='active' LIMIT 1", String.class));
        assertThat(plan).containsIgnoringCase("Index").doesNotContainIgnoringCase("Seq Scan");
    }

    private static Throwable catchThrowable(org.junit.jupiter.api.function.Executable e) {
        try { e.execute(); return null; } catch (Throwable t) { return t; }
    }
}
```

- [ ] **Step 2: Bring up a host-first isolated stack (zero docker) and run the IT**

The IT needs the `integration-test` Postgres reachable with `ab_behavior_event` present. Use the shared host `aura_boot` (or an isolated `dev.sh runtime` if a concurrent session is using it). Then:

Run: `./gradlew :platform:test --tests "com.auraboot.framework.behavior.keyed.KeyedCollectIT"`
Expected: PASS (9 tests). If `relation "ab_behavior_event" does not exist` → re-apply migrations (env-invalid, not code), then re-run.

- [ ] **Step 3: Commit**

```bash
git add platform/src/test/java/com/auraboot/framework/behavior/keyed/KeyedCollectIT.java
git commit -m "test(behavior): real-PG HTTP golden — keyed ingestion, isolation, 403s, index, uniqueness"
```

---

## Task 7: Correct the index wording in SP1 docs

**Files:**
- Modify: `docs/superpowers/specs/2026-06-21-site-key-registry-design.md` (§9.1)
- Modify: `docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md`
- Modify: `docs/handover/HANDOVER-2026-06-21-site-key-registry-sp1.md`

- [ ] **Step 1: Replace `(tenant_id, site_key)` index references with the corrected semantic**

In each file, find the `(tenant_id, site_key)` unique-index phrasing and replace with a note like:

```
~~(tenant_id, site_key) 唯一索引~~ → **全局 `UNIQUE(site_key)`**(SP2 纠错:resolveTenant 跨租户,复合唯一喂不动查询且允许跨租户同 key 串台;见 docs/backlog/2026-06-21-mt-dynamic-table-index-creation-analysis.md)
```

Keep the original line struck/annotated rather than deleting, so the SP1 record stays auditable.

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-06-21-site-key-registry-design.md \
        docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md \
        docs/handover/HANDOVER-2026-06-21-site-key-registry-sp1.md
git commit -m "docs(behavior): correct SP1 index wording to global UNIQUE(site_key) (SP2 finding)"
```

---

## Final Verification (before PR)

- [ ] All unit tests green: `./gradlew :platform:test --tests "com.auraboot.framework.behavior.keyed.*" --tests "com.auraboot.framework.behavior.service.BehaviorCollectServiceAnonymousTest" --tests "com.auraboot.framework.behavior.sitekey.SiteKeyIndexInitializerTest"`
- [ ] IT green (host-first): `KeyedCollectIT` 9/9.
- [ ] Regression: authenticated `/api/collect` still 200 with JWT tenant (existing behavior tests unaffected — run `com.auraboot.framework.behavior.*`).
- [ ] Static gates: `scripts/check-jsonb-typehandler.sh`, `scripts/check-oss-boundary.sh`, `node scripts/validate-permission-codes.mjs` (no new perms — expect 0 drift).
- [ ] Verify the IT commit oid lands on `feat/site-key-anonymous-ingestion-sp2` (`git branch --contains <oid>`), not canonical main.

---

## Self-Review

**Spec coverage:** D1 endpoint → Task 4; D2 global unique → Task 5 + Task 6 assertions + Task 7 wording; D3 dual-trigger → Task 5; D4 abuse baseline → Task 3 (origin/rate/batch) + Task 2 (origin) + Task 6 (real 403s); recordAnonymous → Task 1; CORS (§6) → flagged as build open point (no code change unless IT reveals a preflight block — note: if MockMvc IT passes but a real browser preflight fails, SP4 surfaces it). Regression (§6) → Final Verification. Payload `props` byte cap (spec §5 MAX_PROPS) → **simplified**: batch-count cap (Task 3) + body content-length cap via Spring `server.max-http-request-size` (set in build, documented) subsume per-event props size; noted here so the simplification is explicit, not a silent drop.

**Placeholder scan:** no TBD/TODO; all steps carry real code or exact commands.

**Type consistency:** `recordAnonymous(List<BehaviorEventInput>, long)`, `check(String,String,String,List)→long`, `isOriginAllowed(String,String)→boolean`, `originMatches(String,List)→boolean`, `createFieldIndex(String,String,IndexType)`, `ensureIndex()` — names consistent across Tasks 1/2/3/4/5/6.

**Listener correctness:** the app-ready listener is annotated `@EventListener(ApplicationReadyEvent.class)` (typed) so it fires only on app-ready, not on every event. The unit test calls the method directly (annotation-independent); the typed annotation is what makes the runtime trigger correct.
