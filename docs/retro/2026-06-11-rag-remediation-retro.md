---
type: retro
status: closed
created: 2026-06-11
owner: platform
topic: RAG G1-G9 remediation retro (aura-endgame P6)
---

# RAG Gap Remediation — Retro (2026-06-11)

Scope: review of the RAG subsystem → tracker (`docs/backlog/2026-06-10-rag-system-review-and-gap-tracker.md`)
→ /aura-endgame remediation of G1-G9 on `feat/rag-gap-remediation`.

## 完成核对

Five-item pre-completion review passed with evidence — see
[`2026-06-11-rag-remediation-review.md`](./2026-06-11-rag-remediation-review.md)
(direction / per-gap progress / re-scan / UX with screenshots + 32-pass E2E +
full-OSS 924-pass ×2 / test completeness + /e2e-truth 8/10). One review→fix
iteration: 5 code-review findings (1 P0, 2 P1, 2 P2) all fixed and re-verified
green — no second full /aura-endgame round needed since findings were point
fixes with existing regression nets.

## 过程问题与根因

1. **响应结构变更漏改隐性消费方(P0,review 抓住)**:`/retrieve` 改返回
   `{results, warnings}` 时只改了"已知"前端消费点(playground、E2E),漏了
   CommandPalette。根因:改 API shape 前没有 `grep -rn "knowledge/retrieve"` 全仓
   清点消费方。教训:**改任何 API 响应结构,第一动作全仓 grep 该路径**。
2. **代理对 bug(P1)**:bigram 用 char 索引切 codePoints 填充的 buffer。根因:
   写 Unicode 处理代码时没把"补充平面"列入测试用例集。教训:CJK 文本处理单测
   必含 U+20000+ 用例。
3. **schema.sql ≠ 存量库**:差点只改 schema.sql(fresh-reset 真源)而漏建
   migrations 文件——仓里两套机制并存,review 用"仓内惯例 exemption_check"抓出。
4. **后台任务进程组误伤**:bootRun 作为后台 Bash 任务的子进程被任务清理 SIGTERM;
   用 `nohup ... & disown` detach 解决。
5. **E2E env 契约**:`psql helper` 未带 `PG_DB` 时静默连共享 `aura_boot` 报假失败
   (memory 已有该 gotcha,这次再次验证);`localhost` 解析 ::1 导致 ECONNREFUSED,
   一律用 `127.0.0.1`。
6. **oss-test.sh 不支持 spec 级过滤**(positional filter 不生效,跑了全量两次)——
   定向跑单 spec 用 `npx playwright test -c playwright.oss.config.ts <file>`。

## 为什么这些问题会出现(分类)

- 门禁质量:静态门禁(tsc/compile)抓不到"消费方读旧 shape"(F1)——值得考虑
  API-shape 变更的契约测试;本次靠 review 兜住。
- 输入信息:无缺口——review 文档 + 取证 agent 提供的事实足够。
- 编排:golden-query 扩容 agent 给了硬 ground-truth 约束(存在性逐一断言)效果好,
  无返工;review agent 的 verify-before-flag 纪律(实测编译执行验证 F2)产出了
  全部真发现、零误报。

## 固化(precipitation)

- 升 memory(本会话写入):改 API 响应结构必先全仓 grep 消费方(F1 教训)。
- 不升 canonical:其余各条已有 canonical 载体(psql env-aware 已在 memory;
  worktree/E2E env 契约在 oss-e2e 文档)或属一次性环境噪声。

## Backlog(残留)

见 tracker §8:Phase-2 live eval(需 embedding key)、legacy renderer 清理、
docker 下重跑 check-schema-sql.sh、P2 deferred 列表。
