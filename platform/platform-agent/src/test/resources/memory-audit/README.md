# Memory prompt 装配审计 — Spike-2 tooling

Spike-2 audit infrastructure. See [`docs/backlog/2026-05-27-spike-2-memory-prompt-audit-design.md`](../../../../docs/backlog/2026-05-27-spike-2-memory-prompt-audit-design.md).

## Files

| File | Role |
|------|------|
| `audit-queries.sql` | 4 read-only parameterized templates: sample triples / snippet bundle / extraction volume / dedupe proxy |
| `annotation.schema.json` | JSON Schema for human conflict annotation |
| `README.md` | this file |

## Phase 1 — what's in this PR

Tooling only. No runs against real data. No production code touched.

## Phase 2 — how to use

1. Pick environment with anonymized snapshot of `ab_agent_memory` + `ab_agent_observation` + `ab_agent_memory_access_log`
2. Run Q1 with `:tenant_id`, `:time_window='30 days'`, `:sample_limit=10` → get 10 triples
3. For each triple, run Q2 with the triple's params + `:keyword=''` (skip keyword pass for unbiased sample) → produce `prompt-segments-<ts>.json`
4. Run Q3 → `extraction-volume-<ts>.json`
5. Run Q4 → `dedupe-proxy-<ts>.json`
6. Human reviewer fills `annotations-<ts>.json` per `annotation.schema.json`
7. Java analyzer (`MemoryPromptAuditHarness` Phase 2) computes:
   - Per-tag distribution
   - 矛盾召回率 = (temporal + factual + granularity) / total
   - Extraction call/token cost
   - Distinct-ratio histogram
8. Emit `report-<ts>.md` with B1/B2/B3 recommendation

## Privacy

⚠️ Snippet bundles in `prompt-segments-*.json` contain real `memory_content`. Anonymize before annotation:

- PII via `MetaContext` shadow filter (TBD) OR
- Sanitize names/emails/phone numbers via regex pre-pass before sample export

Annotations land in **enterprise repo only** (`auraboot-enterprise/docs/system-reference/runtime-traces/memory-audit/`) — gated access.

## Run command (phase 2)

```bash
cd platform
./gradlew test --tests '*MemoryPromptAuditHarness*' -PmemoryAudit=true
```

Phase 2 stub is currently `@Disabled` until the env wiring + snapshot pipeline are ready.
