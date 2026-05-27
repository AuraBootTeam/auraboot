# Spike-1: RAG + D7 evaluation harness — design

> **Status**: Phase 1 (infrastructure + seed queries). Phase 2 (full 50-100 query set + actual eval runs) is follow-up.
>
> **Driver**: [`auraboot-enterprise/docs/plans/2026-05/2026-05-27-aurabot-memory-knowledge-assessment-and-plan.md`](../../../auraboot-enterprise/docs/plans/2026-05/2026-05-27-aurabot-memory-knowledge-assessment-and-plan.md) §4 Spike-1
>
> **Strict scope**: data + report only. Forbidden: modify RAG code, change schema, write RAG refactor PR.

---

## §1 Why this harness

Two independent retrieval paths exist (see assessment-and-plan §2.1):

- **Path A** `RagRetrievalService` — hybrid vector + BM25 over `ab_kb_chunk`
- **Path B** `D7CompiledKnowledgeService` — term-hit over file-based JSON pages

No baseline metrics exist for either. Optimization decisions (backlink weighting / RRF / CJK 分词 / Contextual Retrieval / which path to invest in) cannot be made without numbers. This harness provides the baseline.

## §2 Architecture

```
                  golden-queries.json (versioned in repo)
                              │
                              ▼
       ┌─────────────────────────────────────────────┐
       │  RagEvaluationHarness (Spring Boot @Test)   │
       │  - Tagged @RagEvaluation                    │
       │  - Excluded from default ./gradlew test     │
       │  - Run via: -PragEval=true                  │
       └─────────────────────────────────────────────┘
                              │
              ┌───────────────┴────────────────┐
              ▼                                ▼
   RagRetrievalService.retrieve()    D7CompiledKnowledgeService.retrieve()
   (Path A — DB + embedding)        (Path B — file + term-hit)
              │                                │
              └───────────────┬────────────────┘
                              ▼
              RetrievalMetrics.compute()
              (Recall@K / Precision@K / NoAnswer / Latency)
                              │
                              ▼
   docs/system-reference/runtime-traces/rag-evaluation/
      ├── results-<timestamp>-path-a.json
      ├── results-<timestamp>-path-b.json
      └── report-<timestamp>.md
```

**Why integration test (not unit)**:

- Path A needs real Postgres with `pgvector` + `tsvector` indexes + populated `ab_kb_chunk`
- Path B reads real JSON pages from `compiled-knowledge/pages/*.json`
- Mocked search returns nothing useful for evaluation

**Why JUnit-tagged + excluded by default**:

- Setup is heavy (PG + embeddings + pages)
- Run cadence is "before optimization PRs" or "weekly baseline", not "every CI"
- Tag: `@Tag("rag-eval")`, run with `./gradlew :platform:test --tests '*RagEvaluation*' -PragEval=true`

## §3 Golden query schema

`platform/src/test/resources/rag-eval/golden-queries.schema.json` (JSON Schema 2020-12)

Each query entry:

```json
{
  "id": "zh-short-001",
  "language": "zh" | "en" | "mixed",
  "length_class": "short" | "medium" | "long",
  "expected_path": "A" | "B" | "both" | "neither",
  "query": "用户实际问的话",
  "expected_kb_pages": ["pid1", "pid2"],
  "expected_d7_pages": ["compiled.playbook.command-delivery-workflow"],
  "tags": ["命令执行", "权限", ...],
  "notes": "为什么期望这个结果"
}
```

Field rules:

- `id`: kebab-case, prefix encodes traits for ad-hoc grep
- `language`: query 文本主语种
- `length_class`: short < 10 char, medium 10-30, long > 30
- `expected_path`: 期望命中哪条路径
  - `A`: 用户上传 KB
  - `B`: 平台 SOP / D7
  - `both`: 主题在两边都有,fusion 时不应矛盾
  - `neither`: NoAnswer 类(应该没结果)
- `expected_kb_pages` / `expected_d7_pages`: ground truth identifiers
- `tags`: 用于分组聚合(例如所有"权限"类 query 的 recall)
- `notes`: 评估人能看懂为什么这是 ground truth

## §4 Metrics

| Metric | Definition | Goal |
|--------|-----------|------|
| **Recall@K** | `\|retrieved ∩ expected\| / \|expected\|` 取 top-K 后 | 越高越好,baseline → 看 spike 出数据 |
| **Precision@K** | `\|retrieved ∩ expected\| / K` | 越高越好 |
| **NoAnswer-rate(expected=neither)** | 在 expected="neither" 的 query 上,实际返回空的比例 | 越高越好(应该真无答案) |
| **FalsePositive-rate** | expected="neither" 但实际有结果的比例 | 越低越好 |
| **Latency p50/p95** | retrieve() wall clock | 监控 baseline,无目标 |
| **Per-language Recall@5** | 按 `language` 分组的 Recall@5 | **关键产出**:验证 CJK 分词缺陷(zh vs en) |
| **Per-length-class Recall@5** | 按 `length_class` 分组 | 检查"短 query"问题 |
| **Per-tag Recall@5** | 按 `tags` 分组 | 发现"权限类 query 全挂"这种主题缺陷 |

K = 5 是默认值;harness 同时计 K=3 / K=10 备查。

## §5 Phase 1 deliverables(本 spike 当次产出)

- [x] Design doc(本文档)
- [x] JSON Schema (`golden-queries.schema.json`)
- [x] 15 seed queries(5 zh / 5 en / 5 mixed,跨 length_class 与 expected_path)
- [x] Java 骨架:`RagEvaluationHarness` + `RetrievalMetrics` + `GoldenQuery` DTO
- [x] JUnit 5 `@Tag("rag-eval")` + 默认排除配置
- [x] `compileTestJava` 通过(不要求能跑出真数据,需 phase 2 配 PG)

## §6 Phase 2 deliverables(follow-up,不在本 PR)

- [ ] Golden query 从 15 扩到 50-100,覆盖 §4 测试矩阵全 cell
- [ ] PG 测试 fixture:种子 5-10 个 KB documents + embeddings(用真模型或 stub)
- [ ] D7 测试 fixture:复用 `compiled-knowledge/pages/` 现有 15 pages
- [ ] 跑 harness → 产 `results-<ts>-*.json` + `report-<ts>.md`
- [ ] 数据进 `auraboot-enterprise/docs/system-reference/runtime-traces/rag-evaluation/`
- [ ] 报告输入 DDR-A(双路径融合)+ DDR-CJK 决策

## §7 Exit criteria

**Phase 1**(本 PR):
- 设计文档完整
- Schema 可校验
- 15 query seed 含 §4 各维度至少 1 例
- Java 编译通过
- README 说清"如何在 phase 2 时跑起来"

**Phase 2**(后续):
- 全量 query 集就位
- 至少 1 次完整跑通,产出 `report-<ts>.md`
- 报告里数据回答:**Recall@5 中英差距**、**两路径 overlap 率**、**neither query FP 率**

## §8 Forbidden(纪律)

- ❌ 改 `RagRetrievalService` / `D7CompiledKnowledgeService` 任一行
- ❌ 改 `ab_kb_*` / D7 page schema 任一字段
- ❌ 写 RAG 优化 PR(分词、reranker、backlink 等)— 那些是 Spike-5 / Bugfix follow-up 范围
- ❌ 把 phase 2 数据塞进本 PR — phase 2 是独立交付

## §9 关联

- Driver:`auraboot-enterprise/docs/plans/2026-05/2026-05-27-aurabot-memory-knowledge-assessment-and-plan.md` §4 Spike-1
- 与 Bugfix-0 关系:并行,无依赖
- 与 DDR-A 关系:Spike-1 数据是 DDR-A 拍板必备输入
- 与 Spike-5 关系:Spike-5 触发条件 = Spike-1 数据确认 CJK 是真瓶颈
