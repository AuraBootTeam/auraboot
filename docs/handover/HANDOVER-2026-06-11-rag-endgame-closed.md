---
type: handover
status: closed
created: 2026-06-11
owner: platform
topic: RAG review → G1-G10 endgame session, fully merged and closed
---

<!-- no-precipitation: durable RAG lessons were already precipitated into enterprise agent-rules/gotchas outside this OSS docs tree; this file is a closed session record. -->

# Session Handover — 2026-06-11 — RAG endgame closed

## Session Summary

从「review 当下 RAG 系统」出发,完成:架构 review + gap tracker → /aura-endgame 全自动修复
G1-G9(PR #545)→ 残留三项收口含 Phase-2 评估 harness 首轮 live 跑(PR #547)→ 深度复盘 +
教训固化(本 PR + enterprise gotchas PR)。**RAG 线全部 merge 到 main,无未收口分支。**

## Tasks Completed

- [x] RAG 架构 review(4 个 Explore agent 取证 + 主对话复核)→ `docs/backlog/2026-06-10-rag-system-review-and-gap-tracker.md`
- [x] G1 权限注解 ×14 + 权限码注册;G2 CJK bigram(索引/查询对称 + reindex 端点/UI 按钮);G9 四路 ingest 收敛 `KbChunkIngestPipeline`;G5 DDR-A=A1 RRF 融合;G4 context token 预算;G6 指标 + 有界重试(`sys-rag-embedding-retry`,5 次→`failed_permanent`);G8 `/retrieve`→`{results,warnings}`;G3 golden set 15→52 — **PR #545 → main `e51597e17`**
- [x] 残留:billing 权限码注册(0 drift)、legacy renderer 清理、**Phase-2 评估 harness `RagEvaluationPhase2IT`** + 首轮 keyword-fallback live 跑(Path B 0.985/0.909,Path A keyword 腿 0.600/0.454)— **PR #547 → main `5509d777c`**
- [x] 深度复盘(22 个问题全量盘点 + 归因)`docs/retro/2026-06-11-rag-endgame-session-deep-retro.md`;5 条教训固化进 enterprise gotchas;oss-test.sh 误导 Usage 修正

## Tasks In Progress

无(本线闭环)。后续工作全部在 gap tracker §8 backlog,见 Next Steps。

## Key Decisions

| Decision | Chosen | Rationale | Alternatives |
|----------|--------|-----------|--------------|
| CJK 分词 | Java 侧 bigram(无字典、确定性) | 不引 PG 扩展(zhparser 需进每个环境/镜像)、不引第二套搜索栈 | zhparser / ES |
| DDR-A 融合 | A1 上层 RRF(k=60,compiled ×1.5) | 文档自身软建议;低成本可被扩容后的 golden set 验证 | A2/A3(深融合,defer) |
| 重试状态 | 物理列 `embedding_retry_count` + CHECK 扩 `failed_permanent` | 状态不藏 JSON(field-physicalization 红线);存量库走 migrations 文件 | metadata JSONB |
| G7 反馈环 | 只落 trace/指标地基,交互 UI defer | 产品面需要信号设计决策,地基先行 | 全量反馈 UI |
| Phase-2 eval 无 key | 照样实现 harness + keyword 双腿真跑 | "缺 key"只 block 向量腿;测量仪本身与确定性腿可交付,还发现了 G10 | 标 BLOCKED 等 key |

## Files Changed(全部已 merge)

- 后端 `platform/.../rag/`:`KbChunkIngestPipeline`(新)、`CjkBigramSegmenter`(新)、`D7RagFusion`(新)、`RagRetrievalMetrics`(新)、`EmbeddingRetryService`(新)、`RetrievalOutcome`(新)、`RagEvaluationPhase2IT`(新)+ RagRetrievalService/KnowledgeBaseService/Controller/D7ContextAssembler/RagContextProviderImpl/4 个 ingest 服务改造;`MetaPermission` + `default-bootstrap.json`(ai.knowledge.\* + billing.\*);`schema.sql` + `migrations/2026-06-11-rag-embedding-retry.sql`;`SystemTaskInitializer` 注册重试任务
- 前端:`knowledge.$kbPid.tsx`(Reindex 按钮 + warnings toast)、`CommandPalette.tsx`(新 shape)
- 测试:rag 包 208→~230 测试;`tests/e2e/ai/knowledge-base-smoke.spec.ts` 加 reindex 用例;golden-queries.json 52 条
- 文档:gap tracker、review、retro ×2、eval 首跑报告、本 handover;enterprise 5 个 gotcha/agent-rules 文件

## Pitfalls & Workarounds(详见 deep-retro §2,这里只列接手必读)

1. **共享 dev DB(aura_boot:5432)已应用** `2026-06-11-rag-embedding-retry.sql` 同等 ALTER——如有人 reset 该库,migration 幂等可重放。
2. E2E 定向单 spec:**别用 oss-test.sh 传 glob(不过滤)**,用 `npx playwright test -c playwright.oss.config.ts <file>` + 完整 env(`127.0.0.1` 非 localhost;`PG_DB` 必传否则 psql helper 连错库假失败)。配方已写进 enterprise `oss-e2e-and-playwright.md`。
3. slot 栈上跑 `oss-reset-and-init.sh` 需 `FORCE_HOST=1` + 手工映射 env(`POSTGRES_DB→PG_DB`,`SERVER_PORT→BE_PORT` 等,两代工具变量名不同)。
4. 后台任务里起 bootRun 必须 `nohup … & disown`,否则任务树清理时被 SIGTERM(延迟 ECONNREFUSED 难归因)。

## Lessons Learned(完整版见 deep-retro §3-4)

- 主因不是门禁/输入/提示词,是**主对话对自己 inline 写码没有执行强加给 subagent 的取证纪律**(没 grep 消费方 / 没查 DDL / 没盘 migrations 惯例)。
- 两个真门禁空洞已记 backlog:schema↔migrations 配对检查、API shape 消费方契约。
- 5 条 durable 教训已固化 enterprise gotchas(API shape grep / schema 双轨 / 冲突 marker 行锚定 / nohup+disown / E2E env 配方)。

## Current State

### Git
- OSS main = `5509d777c`(含 #545/#547)+ 本 retro PR;RAG 相关分支/worktree 全部 `MERGED_AND_DELETED`
- canonical `/Users/ghj/work/auraboot/auraboot` 仍在 `codex/crm-endgame-gaps`(CRM 会话占用,**勿动**)

### Running Services
无(slot-23 栈已停,`rag-golden` runtime 已 destroy,DB/Redis/Kafka 命名空间已清)。

### Database State
共享 `aura_boot` 已带 `embedding_retry_count` 列 + 新 CHECK(幂等 migration)。

## Next Steps(全部记录在 gap tracker §8,无隐性状态)

1. **owner**:配 embedding key(`ab_cloud_config` embedding provider 填 apiKey+enabled)→ 重跑 `RagEvaluationPhase2IT`(env:`RAG_EVAL_DOCS_PATH`/`RAG_EVAL_D7_PAGES_PATH`)量化向量腿 lift
2. **G10(P1,下一切片)**:检索相关性下限/score floor(eval 实测 10/10 no-answer 误检);评估 harness 即回归网
3. 门禁改进 ×2(schema-migration 配对脚本、API shape 消费方契约)
4. release 打包前 docker 下补跑 `check-schema-sql.sh`
5. P2 deferred 列表(reranker/GraphRAG/KB 侧边栏菜单/结构化 citation 通道)等 G3 数据驱动决策

## Context for Next Session

- 一切起点:`auraboot/docs/backlog/2026-06-10-rag-system-review-and-gap-tracker.md`(权威 tracker,含全部状态/决策/backlog)
- 深度复盘:`auraboot/docs/retro/2026-06-11-rag-endgame-session-deep-retro.md`
- 评估首跑报告:`auraboot/docs/backlog/2026-06-11-rag-eval-phase2-first-run.md`
- 并发检测:`git ls-remote --heads origin '*rag*'`(应为空)
