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

**Phase 2** (`RagEvaluationPhase2IT` — full live eval, env-gated):

```bash
cd platform
RAG_EVAL_DOCS_PATH=/abs/path/auraboot-enterprise/docs/system-reference \
RAG_EVAL_D7_PAGES_PATH=/abs/path/auraboot-enterprise/docs/system-reference/compiled-knowledge/pages \
./gradlew :test --tests '*RagEvaluationPhase2IT*'
# Emits results-<ts>-path-a.json + results-<ts>-path-b.json + report-<ts>.md
# to build/rag-eval-output/ (override with RAG_EVAL_OUTPUT_DIR)
```

Phase 2 notes:
- Skips (does not fail) when `RAG_EVAL_DOCS_PATH` / `RAG_EVAL_D7_PAGES_PATH` are unset
- Imports the docs dir into a KB via `InternalDocImportService` inside the test
  transaction (rolled back); points `D7KnowledgeProperties.pageDirectory` at the
  pages dir for Path B (restored after the run)
- EmbeddingService is NOT mocked — without a configured embedding key the stack
  degrades to its BM25/keyword leg; the actual mode is recorded in the report
  as `embeddingMode: live|keyword-fallback`
- Measurement, not a gate: no recall/MRR threshold assertions; only harness
  integrity (all queries executed, artifacts written and parseable)
- First-run report: `docs/backlog/2026-06-11-rag-eval-phase2-first-run.md`
