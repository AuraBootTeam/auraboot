# Env-Layering PoC — 后续 backlog(2026-05-07)

本文件记录 `feat/env-layering-poc`(13 commits,108 后端测试全绿)**未随 main 合并落地** 的遗留项。每项给出 what / why / 建议 owner 或触发条件,避免散落在 memory、handover doc 或 commit message 里。

**相关产物**:
- 设计 / UX 契约:`~/.claude/plans/auraboot-dsl-environment-ux-contract.md`
- 决策框架:`~/.claude/plans/auraboot-dsl-decision-matrix.md`
- 完整 handover(根因 + reading order):`~/.claude/plans/auraboot-dsl-environment-poc-handover.md`
- 2-week 自动 ping:routine `trig_01PEYxNR9CnLuNBXJthWNXNG`(fires 2026-05-21T02:00Z)

**已闭合状态(本 PoC scope 内)**:
- 全部 reviewer must-fix(#16 plugin-import / #17 写侧 lock guard / #18 backlog 4 项 / #19 UPDATE+DELETE lock guard)
- 主线 12 commits + #19 共 13 commits @ origin

---

## 1. `/admin/promotions` 升级为 DSL 配置(原 #10)

**What**:当前 `web-admin/app/plugins/core-admin/pages/admin/promotions.tsx` 是手写 TSX 列表 + 创建 modal + 行内 detail。UX 契约原计划用 Page Designer + DSL JSON 配置渲染(配合 Command + Model)。

**Why 降级到 TSX**:Promotion 流程包含 **dry-run + 四眼审批 + Diff Viewer 跳转 + 失败回滚状态** 等 4 种交互模式,Page Designer 现有 button/action 模型无法表达 "validate 按钮看 status 决定 enable / apply 按钮在 VALIDATED 时打开 reason modal" 这类 conditional UI。在 PoC 时间盒内做不进 Page Designer。

**建议触发**:Page Designer 加入 **conditional button enabled-state DSL**(如 `enabledWhen: '${status} == VALIDATED'`)+ **inline action modal DSL**(reason 字段 → 弹窗收集)后,promotions.tsx 可整页改写为 `config/pages/ab_promotion_list.json` + `ab_promotion_detail.json`。

**Owner 决策点**:是否值得为单页 promotion 管理推动 Page Designer 表达力升级?如客户主要访问入口是 PR/CI 集成而非 UI,可永久 WONT_DO。

**OSS 入口**:`AGENTS.md` 「平台管理页 / 操作 DSL 本身」豁免列表。

---

## 2. E2E happy-path spec 实跑 + `/e2e-truth` 审计(原 #13)

**What**:`tests/e2e/admin/env-layering-happy-path.spec.ts` 7 cases authored,**未执行**。覆盖 D1/D2/D4/D6/D9/D11/D12/D14。

**Why 未执行**:Dev stack(8080 backend + 3000 vite + bff)未启动,且与并行 OSS worktree 共享 `aura_boot` DB(根因详见 handover doc § 2 — 类似情况见 [`feedback_parallel_worktree_db_share`](../../../../.claude/projects/-Users-ghj-work-auraboot/memory/feedback_parallel_worktree_db_share.md))。

**建议触发**:per-worktree DB 隔离落地后(参考 handover doc § 2 选项 A),按

```
cd /Users/ghj/work/auraboot-worktrees/env-layering-poc
LOG=/tmp/pw-env-layering-$(date +%Y%m%d-%H%M%S).log
./scripts/oss-test.sh tests/e2e/admin/env-layering-happy-path.spec.ts 2>&1 | tee "$LOG"
```

**通过标准**:7 cases 全 PASS;`/e2e-truth` skill 审计 0 PUT-API 兜底 / 0 threshold 放宽 / 0 skip 包装产品缺口 / 0 retry 兜底。

**Out of scope of this spec**(单独 follow-up):full promotion lifecycle(validate → apply → diff visit)需要 PageSchema 源页面 fixture helper —— 当前没有公开测试工具创建 PageSchema。

**尝试记录(2026-05-07)**:本次会话尝试了一次端到端实跑,**未成功**。流程与卡点:

1. 端口冲突追逐:Docker 占 5174 → 切 5175;sibling worktree 占 5173/6443 → 切 5175/6445。BFF 默认 3501 也被占 → 切 3502。
2. 启动了完整并行 stack:Spring Boot @ 6445(独立 DB `aura_boot_env_layering`)、Vite @ 5175、BFF @ 3502。
3. **卡点**:BFF 对 `Origin: http://localhost:5175` 返回 `Invalid CORS request`,`auth.setup.ts` 三个步骤过了但 happy-path EL-001 跳到 `/login`。
4. 诊断:`web-admin/app/server/bff.server.ts` 的 `ALLOWED_DEV_PORTS` 硬编码白名单不含 5175。用 sed 加进去后 BFF 仍拒,怀疑 tsx watch 没热加载或有第二条 CORS 通路。

**结论**:**E2E 不能在并行 worktree 里轻量跑**——除了 DB 隔离(已知)还有:
- BFF 的 `ALLOWED_DEV_PORTS` 白名单只认 5173/5174/6443,跑 5175 必须改源码并完整重启 BFF;
- 多个 dev session 并存时端口需要主仓 + 至少 2 个非冲突值,且必须先 `lsof` 探测。

**下次尝试建议**:
- 退回主 worktree(端口默认 5173+3501+6443),停掉所有其他 dev session;或
- 把 `bff.server.ts` 的 ALLOWED_DEV_PORTS 改成读环境变量(见下方 follow-up #6)。

**当前进度**:`tests/e2e/admin/env-layering-happy-path.spec.ts` 已合入分支(7 cases);`auth.setup.ts` 在自定义端口下能过(说明 fixture 兼容性 OK);剩余卡点纯环境而非 spec 本身。

---

## 3. `lock` / `unlock` 操作接审计日志

**What**:`Environment.locked_by` / `locked_at` / `locked_reason` 字段记录在 entity 上,`/api/admin/environments/{pid}/lock` 端点写日志到 `log.info`。但**没有写入** `ab_permission_audit_log`。

**Why 未做**:#18 review 把它列为 backlog 但 PoC 时间盒只关了前 4 项(race / classpath scan / nested bypass / 404 envelope)。

**建议触发**:做 `@RequirePermission("environment:lock")` 注解时一并把 `EnvironmentController.lock/unlock` 改成走 `AuditLogService.record(...)`。需要看现有 `ab_permission_audit_log` schema(如果还没有,先建)。

**合规价值**:lockedReason 已经落到 `ab_environment` 表里,但事后能否从 audit log 查"谁在何时锁/解锁了哪个环境 + 原因"是合规审计的标准要求。

**估时**:小到中等(2-4 hours),取决于审计日志表是否已存在。

---

## 4. `ab_page_schema.env_id` SET NOT NULL

**What**:`#16` 已修 plugin-import 路径会 stamp env_id;`#17 + #19` lock guard 已就位。但 schema.sql 里 `env_id` 仍是 nullable,因为**遗留行**(PoC 之前已存在的 `ab_page_schema` 行)有 NULL env_id。

**Why 未做**:需要一次 backfill migration:每行根据 tenant_id 找到 default env(可能要同时建 default env)+ `UPDATE ab_page_schema SET env_id = ?`。这是数据迁移,不是 schema 改动。

**建议触发**:下次 OSS reset-and-init 之后,在 `schema.sql` 末尾加一段 backfill SQL,然后:

```sql
ALTER TABLE ab_page_schema ALTER COLUMN env_id SET NOT NULL;
ALTER TABLE ab_page_schema_history ALTER COLUMN env_id SET NOT NULL;
ALTER TABLE ab_page_schema
  ADD CONSTRAINT fk_page_schema_env FOREIGN KEY (env_id) REFERENCES ab_environment(id);
```

**风险**:已有数据如果有 tenant 不存在 default env,backfill 失败。需要先跑 `findOrCreateDefaultId` for-each-tenant。

---

## 5. UPDATE/DELETE lock guard 边界 case 测试

**What**:#19 已经 ship `EnvWriteLockGuardInnerInterceptor` 拦 UPDATE / DELETE,3 测试覆盖直接 update / delete / bypass。但**未覆盖**:
- 多表 JOIN 的 UPDATE(目前用全词匹配,不会匹配 JOIN 中的 alias)
- 子查询里出现 @EnvScoped 表名(误判风险)
- 批量 SQL `IN (...)` 含多个 page pid 的 batch DELETE

**Why 未做**:PoC 时间盒;3 个简单 case 已锁了主路径。

**建议触发**:做 production-grade 防漏判时,引入 JsqlParser 做正式 SQL 解析(MyBatis-Plus 已有这个依赖),从 `Statement` → `Update`/`Delete` 提取 target table 列表,逐个判 @EnvScoped。同时加 ≥ 5 个 edge case test。

**估时**:中等(4-8 hours)。

---

## 6. BFF `ALLOWED_DEV_PORTS` 改为环境变量驱动(本次新增)

**What**:`web-admin/app/server/bff.server.ts` 的 `ALLOWED_DEV_PORTS` 是硬编码白名单(`['3000', '3500', '5173', '5174', '6443']`)。任何并行 dev session(worktree、PoC 实跑)如果用了非默认端口,BFF 都会用 `Invalid CORS request` 拒掉前端登录调用。

**Why 重要**:这是无声坑——前端控制台只看到 401 + 重定向,不查 BFF 日志根本不知道是 CORS 拒绝。本次 PoC 实跑就是被这个挡住的。

**建议方案**:改成读 `BFF_ALLOWED_PORTS` 环境变量,默认值保留现有 5 项;并行 stack 启动脚本通过 `BFF_ALLOWED_PORTS=5175,6445` 注入。

**估时**:30 分钟(单点改动 + 启动脚本协调)。

**Owner**:OSS 平台维护者。

---

## 备注:不是 PoC 范围内的 deferred

以下事项虽然在过程中讨论过但**不在本 PoC 关注**,owner 自行决定 priority:

- **DSL 跨实体校验**(`#36 §三十六` 自标 ⚠️ expression 模式 best-effort)— 这是 DSL 平台能力问题,与 env layering 正交。
- **AuraBootObjectHandler SRP 切分** — reviewer 提到 timestamp + envId 双职责,但目前没引发 bug。等到引入 audit-log fill 时一并切分更合算。
- **大插件(224 页 PCBA-ERP)在 Diff Viewer 性能** — UX 契约原 risk;PoC 没真跑 224 页,等 #2 e2e 跑过再评估。
