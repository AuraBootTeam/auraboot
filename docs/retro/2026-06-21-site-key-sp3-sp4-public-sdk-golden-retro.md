---
type: retro
status: closed
created: 2026-06-21
slug: site-key-sp3-sp4-public-sdk-golden-retro
distilled_to:
  - auraboot-enterprise/docs/agent-rules/engineering-gotchas/backend-spring-db.md (mt_ index AFTER_COMMIT + non-request-thread MetaContext correction + public-CORS gotcha, ENT #647)
---

# Retro — site-key SP3 (public SDK) + SP4 (anonymous keyed golden)

> 配套 OSS PR #1013(SP3 SDK + SP4 golden + 3 fixes)+ ENT PR #647(canonical 纠错)。
> 三个 production bug 的技术细节见 spec `docs/superpowers/specs/2026-06-21-site-key-sp3-sp4-public-sdk-and-golden-design.md` §4.5;本文只记**过程教训**。

## 一句话

SP1+SP2 之上把匿名遥测打通到客户已发布应用:SP3 给 SDK 加零依赖公开模式(可 `<script>` 嵌入),SP4 第一次跑**真 plugin import → 初始化器 → createFieldIndex** 活路径,当场抓出并修掉 3 个 compileJava+单测+SP2 in-process IT 全绿却坏的 bug。

## 做对的

- **真栈 golden 是唯一能抓出这 3 个 bug 的层**。SP2 spec pitfall #5 已诚实标注「createFieldIndex 真活路径需注册模型、deferred 到 SP4」——SP4 兑现了这个承诺,没让「单测绿」骗过去。印证 AGENTS §2.2 组件间 seam 须 assembled-product 运行时门禁。
- **每个失败都先取证再下结论(§15)**。索引没建 → 没有臆断「事务可见性」,而是读 backend log 拿到确切异常(先 `Table does not exist`,再 backstop 的 `Tenant context is required`),发现是**两条触发各崩一处**的两个独立 bug,不是一个。第一版 AFTER_COMMIT 假设只对了一半(只覆盖 import 触发),backstop 的 tenant-context bug 是 drop-index-重启 实验才暴露的。
- **多 checkout 归属纪律**:canonical OSS checkout 停在别会话的 codex 分支,全程在隔离 worktree(off origin/main)+ 隔离 runtime(slot 66)操作,零干扰并发会话;收口 destroy runtime + 验端口释放。
- **悲观结论先做最便宜的推翻实证**:`MetaContext.getCurrentTenantId()` 我以为返 null,单测一跑发现**抛** `not initialized`——save/restore 那行自己就是崩点,改成无条件 set/clear。

## 弯路 / 返工

- **backstop 第一版 save-previous 写错**(用 `getCurrentTenantId()` 取 previous,它在未初始化线程抛异常)。代价:1 轮单测。根因 `[D 验证]`:写「读当前 tenant」前没确认它 unset 时的行为(抛 vs null)。已在 canonical 标注。
- **golden 反复小坑**(各 1 轮):env-drift 用过长 eventId(>varchar(40)→500)/ psql `boolean||text` 渲染 `true` 非 `t` / 缺 `ab_behavior_event`(host 栈 schema.sql 基线落后于 migration)/ 缺 `core-dashboard`(看板页不在 site-key 插件)。都是真栈 fixture/契约细节,被 golden 逐个挡下、没 ship 出去。
- **必需 docs gate 被别会话旧文档债卡住**:我的文档干净,但 PR 触发的 Documentation Quality Gate 在 8 个别会话遗留文档(frontmatter 枚举/created + 跨仓 distilled_to)上红。owner 决定 PR 先挂着、不动别人文档、待债清或 admin-merge。

## 教训固化

- [x] OSS spec §4.5 记 3 bug。
- [x] ENT canonical 纠错(PR #647,待 merge):`backend-spring-db.md` 把「双触发都 @EventListener」改对(import→AFTER_COMMIT / 非请求线程→设 MetaContext)+ 新增公开端点 CORS 条目 + AGENTS 速查表行。
- [x] memory active-work 更新。
- 通用教训(已进 canonical):① 插件 import 完成后做 DDL/schema/meta 写的 listener 一律 `@TransactionalEventListener(AFTER_COMMIT)`;② 非请求线程调平台 meta service 前必设 `MetaContext` tenant。

## 残留 / 下一步

- OSS #1013 + ENT #647 待 owner merge(#1013 等 docs-gate 债清或 admin override)。
- follow-up(非本 SP):`recordBatch` 对超长 client 字段抛 500 而非 skip/400(公开端点 per-field 长度兜底);controller-authz 既存未 baseline 的 `BehaviorCollectController`/`TestImBroadcastController`。
- 遥测平台其它线:Kafka 解耦 ingestion / §5.4 UI 元素身份治理 / OTel。
