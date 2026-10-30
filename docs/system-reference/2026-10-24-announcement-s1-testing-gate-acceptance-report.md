---
type: system-reference
status: active
created: 2026-10-24
updated: 2026-10-24
---

# 公告 S1 受控生命周期验收记录

## 结论与适用范围

**claim_level：S1 定向功能与桌面交互已验证，待 PR 合并。** 本记录不是完整公告产品、BPM 或生产上线验收。需求事实源仍为 [公告管理设计](./announcement-management.md)，S1 实际接口、状态和安装约束见其 §17。

本次实现草稿新增/修改/删除、CAS 与持久幂等、操作审计、不可变历史版本、存量迁移、历史公告撤回与建立修订草稿。Web 使用现有 DSL 页面。新公告发布明确拒绝；没有接入通知投递、BPM 引擎或小程序。

验证源为 `bc5a0818ec107325eb18925c0dc0a9e735ce79db`，分支 `codex/announcement-management`。最终契约和验收资料提交与此版本的运行时代码、DSL、浏览器/API 驱动相同；后续差异为文档、证据、OpenAPI 快照和生成的 TypeScript 声明。声明更新另经类型检查，不能把源码等价说明当成新的执行轮次。

## 实际执行与证据

| 层次 | 实际执行 | 裁决与边界 |
| --- | --- | --- |
| 后端 hermetic unit | 9 类，161 例，0 失败/跳过 | 生命周期、处理器管线、目标授权、字段映射、计算字段、模型写守卫、控制器授权、页面 schema；[类级结果](./evidence/announcement-s1-2026-10-24/backend-unit-summary.json) |
| Spring + PostgreSQL integration | 2 例通过 | 实际事务内创建/重放/编辑/CAS/非法字段/发布拒绝/删除/审计和接收读取；`AnnouncementControllerIntegrationTest`；不是模拟数据库 |
| 前端 hermetic unit | 7 文件，79 例通过 | commandRequestPolicy、useActionHandler、formPage；没有以纯工具测试替代页面交互 |
| API + 真实后端/数据库 | 3 例通过，0 跳过/失败/重试 | 命令幂等、变更意图冲突、并发一胜一冲突、动态写旁路拒绝、REST 适配；[runner](./evidence/announcement-s1-2026-10-24/api-results.json) |
| Chromium + Web/BFF/后端/数据库 | 7 例通过，0 跳过/失败/重试 | 真实菜单进入新增/筛选/详情/编辑/发布拒绝/删除/历史撤回修订/观察角色；[runner](./evidence/announcement-s1-2026-10-24/browser-results.json) |
| 视觉与交互 | 9 场景，15 张最终原图逐一打开复核 | `agent-vision`，桌面 1280×720、zh-CN，部分为控件裁图；[矩阵与逐图记录](./evidence/announcement-s1-2026-10-24/manifest.md) |
| 类型与内部契约 | `pnpm typecheck`、生成器 `--check`、契约工具 38 例通过 | [捕获快照比较](./evidence/announcement-s1-2026-10-24/openapi-drift.json)；只证明捕获生产者契约一致，不代表所有 API 已端到端测试 |
| 数据迁移 | 141 条 Flyway 新库迁移/validate、生成快照漂移检查通过 | 真实 PostgreSQL 升级夹具与不可变触发器负向实验见下文 |

浏览器/API 共同轮次为 `2026-10-24T12-13-40-542Z-79a0bbf5-3751-4ecc-9015-f762b65d89ad`，稳定 runtime `announcement-management`，OSS base + `core-announcement`，slot 1。浏览器为 Playwright 1.60 Chromium / macOS。服务从干净源绑定启动；测试过程中不更换业务实现。API 补验发生在同一实例，新增的生成声明不影响其运行时代码。

可复核入口：

- `web-admin/tests/e2e/announcement/announcement-lifecycle.spec.ts`：7 个浏览器场景及页面、请求和数据库结果断言。
- `web-admin/tests/api/announcement-controlled-writes.spec.ts`：3 个 HTTP 场景。
- `platform/platform-app/src/test/java/com/auraboot/framework/workbench/AnnouncementControllerIntegrationTest.java`：真实 Spring/PG 事务集成。
- `scripts/tests/announcement-migration-upgrade.sql`：在事务内构造旧表、应用实际迁移并 ROLLBACK。
- [动作分母与证据 manifest](./evidence/announcement-s1-2026-10-24/action-manifest.json)、[机器解析报告](./evidence/announcement-s1-2026-10-24/report.html)。报告保留 7 个后续切片未验证行；25 个通过行含 10 个 runner 场景和 15 张视觉原图，不能解读为 25 个测试或公告功能 100% 完成。

## 覆盖、权限和反证

**feature_action_matrix：** 六个命令均有实际结果断言：create/update/delete/publish（预期拒绝）由 API/浏览器覆盖；archive/republish 由历史公告浏览器场景覆盖。列表、表单、详情经产品菜单进入。静态收集器仅识别 6 个命令和 3 个页面，不能推导部分动态命令地址与菜单跳转；保留其盲区，以精确 runner 证据对账，未将静态扫描计为执行。

**permission_negative：** 真实创建观察角色、权限和用户，经 BFF 登录后隐藏新建及修改入口，直接命令返回 403；通用动态写入口返回拒绝。目标授权在幂等结果读取前重查当前身份和动作，不因旧缓存或 tombstone 绕过撤权。另有同一全局用户跨租户目标的按需 HTTP 探针，断言对方记录和审计不变，返回业务拒绝；[探针结果](./evidence/announcement-s1-2026-10-24/tenant-isolation-probe.json)。该探针来自上一真实运行轮次，业务 Java 代码一致；不计入本轮 3 个 API 用例。

**backend_evidence：** 单测逐类计数可追溯；[JaCoCo 记录](./evidence/announcement-s1-2026-10-24/backend-coverage-summary.json)为 unit 行覆盖：生命周期 145/168（86.3%）、模型守卫 59/65（90.8%）、目标范围 145/165（87.9%）、处理阶段 393/486（80.9%）。**integration_coverage：未测量真实栈行覆盖**，没有用这些 unit 百分比声称集成覆盖达标。

**artifact_evidence：** 新库实际执行 141 条迁移，生成 schema-current.sql 后独立漂移检查和 Flyway validate 通过。旧库夹具验证 active/archived 与 priority 映射、migration_cohort 来源、停用成员排除、不可变版本/审计、跨租户及跨公告版本指针拒绝。负向实验只在夹具事务内关闭版本不可变触发器，测试按预期以 “Version mutation was permitted” 失败（exit 3）；恢复原夹具后再次通过且无残留测试 schema。没有在产品库关闭触发器或伪造历史已读/审批事实。

**浏览器状态与反馈：** 编辑验证五字段持久化、优先级/置顶及观察版本；删除先确认再验证真正空态；历史版本撤回后读者不可见，修订保留旧发布信息且不能删除历史。发布错误为可读业务信息，状态保持草稿。截图只证明当时可见状态；网络/数据库断言证明动作结果。

## 契约修正及全仓检查

首轮 PR CI 的 Docker Quickstart 在内部 OpenAPI 漂移检查失败，原因是本次 REST PID、幂等/版本参数和返回值变化尚未同步快照，已修正。快照来自 CI run `40092440881` 的 `internal-api-live` artifact；生产者提交 `27d631c91769e9e1c0baed2172de97662dd0a42e` 与本轮 Java 实现相同。采用实际完整组合快照标准化后生成类型，未使用仅 base 本地服务删去 agent 端点。契约工具比较、类型生成 check 及 TypeScript 检查均已通过。最终 PR CI 状态以 GitHub 当前提交为准，本报告不提前宣称其全部通过。

OSS 边界、reset/init 契约（10 例）、命令可达性、spec 注册、文档治理及 diff 检查通过。命令可达性仍有基线警告。全仓 test-system 检查**未全绿**：脚本索引原有两个未登记项 `scripts/application/run-product-release-archive-stage.sh`、`scripts/project-delivery-demo.sh`。已通过基线树确认两脚本及索引在本任务前存在且本次未改动；此项单列为既有仓库债务，不宣称修复或通过。

## 重跑及证据信任

在已分配的 runtime 内使用 `scripts/oss-golden-stack.sh env announcement-management` 获取实例环境，再从 `web-admin` 执行：

```sh
PW_PROFILE=full pnpm exec playwright test --project=auth --no-deps \
  --grep 'authenticate as admin' --workers=1 --retries=0
PW_PROFILE=full pnpm exec playwright test --project=api --no-deps \
  tests/api/announcement-controlled-writes.spec.ts --workers=1 --retries=0
PW_PROFILE=full pnpm exec playwright test --project=chromium --no-deps \
  tests/e2e/announcement/announcement-lifecycle.spec.ts --workers=1 --retries=0
```

须设置该实例的 `PW_ADMIN_STORAGE_STATE`、`PW_ARTIFACT_DIR`、`PW_RESULTS_JSON`；认证状态留在外部证据目录，不进 Git。使用配置的 reporter 输出 JSON；若命令行改用 `--reporter=line,json`，JSON 会输出至日志，须解析真实日志而不是制造结果。本次提交的 runner JSON 是原始结果的去环境路径投影，保留用例名、状态、duration、retry 与统计，不包含 cookie/token/auth state。

**skip_fixme_threshold_retry_audit：** 本次 7+3 场景均实际执行，没有 skip/fixme、运行时前提缺失静默返回、放宽阈值或失败后重试冒充首次成功。最终截图使用 `animations: disabled` 完成有限 CSS 过渡，仍等待真实业务终态；没有任意 sleep 或用截图掩盖行为失败。早期页面与捕获问题修复后重新执行，保留外部历史轮次；最终验收只引用本轮原图。报告生成器自测通过，报告能保留未测项而非全绿。

## 未运行和剩余边界

**did_not_run：** 移动/小程序、窄屏和读屏全流程、外部渠道、受众编辑/展开、审批/免审发布、调度/outbox、知悉与催办、统计导出、完整 aura-bpm 组合部署、所有 OSS 页面的回归、生产迁移和生产负载。它们属于后续切片，详见设计 §11、§15–17；不能以本报告替代。

**remaining_blockers：** S1 没有遗留的已知功能阻断；仍有上述全仓脚本索引基线失败，最终远端 CI 需按 PR 当前提交观察。发布继续关闭，直至 S2/S3 授权、冻结名单与可靠分发完成；BPM 接入另按 S4–S6 公共契约实施。此 PR 未获合并或上线授权。

**allowed_claim：** “S1 受控草稿与历史撤回修订已实现并完成所列真实栈及桌面验证，设计和契约已同步，已提交 PR 供评审。” 不允许声称完整公告管理、BPM 集成或全仓所有门禁已通过。
