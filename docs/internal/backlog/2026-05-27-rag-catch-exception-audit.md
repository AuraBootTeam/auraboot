# RAG catch (Exception) audit — Bugfix-0 deliverable

> **Status**: complete for缺陷 2 fix + 缺陷 3 documentation; 4 candidates deferred pending RAG module owner design-intent decision.
>
> **Date**: 2026-05-27
> **Scope**: `platform/src/main/java/com/auraboot/framework/rag/service/*.java` (6 files, 17 `catch (Exception)` blocks)
> **Driver**: [`docs/plans/2026-05/2026-05-27-aurabot-memory-knowledge-assessment-and-plan.md`](../../../../auraboot-enterprise/docs/plans/2026-05/2026-05-27-aurabot-memory-knowledge-assessment-and-plan.md) §4 Bugfix-0
> **Rule reference**: [`docs/standards/core/catch-exception-pattern.md`](../../../../auraboot-enterprise/docs/standards/core/catch-exception-pattern.md)

---

## Full classification table

Decision tree per `catch-exception-pattern.md`: Q1 loop tolerance? Q2 best-effort fallback + 替代路径? Q3 top-level boundary? Q4 wrap-and-rethrow? Else → A1 anti-pattern.

| File:line | Pattern | Status | Action this PR |
|-----------|---------|--------|----------------|
| `RagRetrievalService:70` | P2 (fallback hybrid → keyword) | ✅ | none |
| `RagRetrievalService:127` | P2 (fallback hybrid → vector-only) | ✅ | none |
| `RagRetrievalService:163` (keywordSearch) | candidate A1 weak (log.error present, but no further fallback) | ⏳ deferred | Javadoc marker added; owner review pending |
| `RagRetrievalService:210` (vectorOnlySearch) | candidate A1 weak (log.error present, but no further fallback) | ⏳ deferred | Javadoc marker added; owner review pending |
| `RagRetrievalService:309` (hasActiveKnowledgeBases) | **A1 confirmed (no log, no fallback, masks DB error as "no KB")** | ✅ **FIXED** | try/catch removed; exception propagates to AuraBotChatService:619 outer boundary |
| `RagDocumentSyncListener:68` | P3 (event listener top-level boundary) | ✅ | none |
| `RagDocumentSyncListener:160` | P2 (chunks stored, embedding retriable) | ✅ | none |
| `RagDocumentSyncListener:199` (readRecord) | candidate A1 weak (returns null + log.error) | ⏳ deferred | owner review pending |
| `RagDocumentSyncListener:239` (hashContent) | P2 weak (substitute = Objects.hashCode), **no log** | ⏳ deferred | minor: should add log.warn — left for follow-up |
| `EmbeddingService:77` | P1 (per-batch loop tolerance) | ✅ | none |
| `EmbeddingService:157` | P2 (config lookup graceful — log.debug fits "not configured" case) | ✅ | none |
| `DocTranslationService:195` | candidate A1 weak (returns null + log.error) | ⏳ deferred | owner review pending |
| `DocTranslationService:289` (hashFile) | P2 weak (substitute = "unknown"), **no log** | ⏳ deferred | minor: should add log.warn — left for follow-up |
| `DocumentProcessingService:114` | P2 (chunks retriable, has explicit comment) | ✅ | none |
| `DocumentProcessingService:127` | P2 (markFailed substitute path) | ✅ | none |
| `InternalDocImportService:115` | P1 (per-file import loop) | ✅ | none |
| `InternalDocImportService:177` | P2 (embedding retriable) | ✅ | none |

---

## Fix detail — `RagRetrievalService.hasActiveKnowledgeBases`

### Before

```java
public boolean hasActiveKnowledgeBases(Long tenantId) {
    try {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_knowledge_base WHERE ...",
                Integer.class, tenantId);
        return count != null && count > 0;
    } catch (Exception e) {
        return false;     // ⚠️ silent — no log, no rethrow
    }
}
```

### Decision-tree analysis (A1 confirmed)

- **Q1 loop tolerance?** No — single query, not in loop
- **Q2 fallback + 替代路径?** No — `return false` is wrong-result not alternative-path
- **Q3 top-level boundary?** No — ordinary service method
- **Q4 wrap-and-rethrow?** No — neither logged nor thrown
- **Outcome: A1 anti-pattern**, no exemption clause applies

### Caller safety

```java
// AuraBotChatService.java:619-628
try {
    if (!hasExplicitKbs && !ragContextProvider.hasActiveKnowledgeBases(tenantId)) {
        return "";
    }
    String context = ragContextProvider.retrieveContext(tenantId, ...);
    return context != null ? context : "";
} catch (Exception e) {
    log.debug("RAG context resolution failed: {}", e.getMessage());
    return "";
}
```

Caller already wraps RAG in outer try/catch, so propagation from `hasActiveKnowledgeBases` gives operators observable error in logs while user-visible response degrades gracefully via outer boundary.

### After

```java
public boolean hasActiveKnowledgeBases(Long tenantId) {
    Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ab_knowledge_base WHERE ...",
            Integer.class, tenantId);
    return count != null && count > 0;
}
```

Try/catch removed entirely — exceptions bubble to outer boundary. Cleaner than "add log + rethrow" because removes the anti-pattern surface area.

### Test

```java
@Test
@DisplayName("hasActiveKnowledgeBases propagates DB error (no silent swallow)")
void hasActiveBubblesExceptionUpToCaller() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L)))
            .thenThrow(new RuntimeException("db"));
    assertThrows(RuntimeException.class, () -> service.hasActiveKnowledgeBases(1L));
}
```

Replaces existing `hasActiveSwallowsException` test that canonicalized the bug. Existing positive-path tests (`hasActiveTrue` / `hasActiveZero` / `hasActiveNull`) unchanged and still pass.

---

## Deferred items (require RAG module owner)

### Cluster 1 — Search fallbacks returning empty list

`RagRetrievalService:163` (keywordSearch) and `RagRetrievalService:210` (vectorOnlySearch) both `catch → log.error → return empty`. They are the bottom of the fallback chain (hybrid → vector-only → keyword-only).

Two valid interpretations:

- **(a) P2 weak**: returning empty is the substitute path — LLM proceeds with no RAG context, response degrades gracefully. Owner documents this rationale in Javadoc and we're done.
- **(b) A1 weak**: empty masks DB errors as "no results", obscuring real failures. Owner replaces with propagation to AuraBotChatService:619 outer boundary (same fix as缺陷 2).

This PR adds Javadoc markers to both methods flagging them for owner review.

### Cluster 2 — Returning null with log

`RagDocumentSyncListener:199` (readRecord), `DocTranslationService:195` (LLM translation). Both pattern: catch → log.error → return null. Callers check for null. Could argue P2 weak. Defer to owner review.

### Cluster 3 — Hash fallback without log

`RagDocumentSyncListener:239` (hashContent), `DocTranslationService:289` (hashFile). Both fall back to a substitute value (Objects.hashCode / "unknown") but **lack any log statement**. Strict P2 requires log.warn at minimum. Mechanical fix; not bundled here to keep Bugfix-0 minimal.

---

## What this PR includes

- [x] Audit table for all 17 catch blocks (this doc)
- [x] Fix `hasActiveKnowledgeBases` (try/catch removed)
- [x] Update test from "asserts swallow" to "asserts propagation"
- [x] Add Javadoc markers to `vectorOnlySearch` and `keywordSearch` flagging owner review
- [ ] Cluster 2 deferred (4 sites, 1 file changes)
- [ ] Cluster 3 deferred (2 sites, mechanical log.warn additions)

## Verification

```
./gradlew :test --tests 'com.auraboot.framework.rag.service.RagRetrievalServiceBranchTest'
BUILD SUCCESSFUL — all branch tests pass including new hasActiveBubblesExceptionUpToCaller
```
