# RAG + D7 evaluation — golden query set

Spike-1 evaluation infrastructure. See [`docs/backlog/2026-05-27-rag-d7-eval-harness-design.md`](../../../../docs/backlog/2026-05-27-rag-d7-eval-harness-design.md).

## Files

| File | Role |
|------|------|
| `golden-queries.schema.json` | JSON Schema 2020-12 — versioned ground truth schema |
| `golden-queries.json` | Versioned ground truth set. Phase 1 seed = 15 queries |
| `README.md` | This file |

## Adding a query

1. Pick next sequence number for `<lang>-<length>-NNN` id
2. Author the entry — language / length_class / expected_path / query / expected_*_pages / tags / notes are all required
3. Bump `version` in `golden-queries.json` (semver patch for additions, minor for schema change)
4. `notes` must explain why expected_* is ground truth — human review depends on this

## Running the harness

**Phase 1** (this PR — schema + parsing only):

```bash
cd platform
./gradlew test --tests '*RagEvaluationHarness*'  # goldenQueriesParse passes
```

**Phase 2** (follow-up — full eval):

```bash
cd platform
./gradlew test --tests '*RagEvaluationHarness*' -PragEval=true
# Emits results-<ts>-*.json + report-<ts>.md to
# ../../auraboot-enterprise/docs/system-reference/runtime-traces/rag-evaluation/
```

Phase 2 requires:
- Postgres with `pgvector` + populated `ab_kb_*` fixtures
- D7 compiled-knowledge pages already present in `auraboot-enterprise/docs/system-reference/compiled-knowledge/pages/`
- `RagRetrievalService` + `D7CompiledKnowledgeService` reachable via `@SpringBootTest`
