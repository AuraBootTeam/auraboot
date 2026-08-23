---
type: retro
status: active
created: 2026-08-23
relates_to:
  - plugins/crm/config/commands/crm_activity_common.json
  - plugins/crm/config/named-queries.json
  - plugins/crm/config/pages/crm_activity_common_detail.json
  - web-admin/tests/e2e/crm/crm-contact-followup-lifecycle-parity.spec.ts
---

# CRM 跟进 Web 主链测试门禁验收报告

`allowed_claim: filesystem-and-static-contract-verified; real-stack-verification-did-not-run`

本报告只裁决 T06 的 OSS 跟进切片。Cordys 固定基线仍为 `v1.8.1 / ab96c96`；开发阶段未做业务数据迁移；移动端继续留在分母；不得把本报告外推为 W1 或 Cordys 全产品打平。

## 1. 范围与实现

- 跟进编辑不允许把计划改成记录或反向改写类型。
- `start / complete / cancel` 仅允许 `task` 计划，完成与取消保持终态。
- 计划和记录使用不同的 L4 删除命令、可见条件和不可恢复确认文案。
- 线索、联系人、商机统一通过 `crm_activities_by_object` 聚合 direct anchor 与 relation graph，并按活动 PID 去重。
- 客户时间线同时聚合客户、联系人、商机的 direct/relation 锚点并去重。
- 新增 `crm_follow_record_pool_list`，仅返回非任务跟进记录，保留关联对象和 anchor 计数。
- 活动详情保留既有 `record-comments`，新增只读跟进履历；未修改已 VERIFIED 的评论、@提及和通知实现。

## 2. Feature / Action Matrix

| 行动点 | surface / dependencies / authority / driver | 当前证据 | 裁决 |
| --- | --- | --- | --- |
| 编辑保留 plan/record 类型 | command / hermetic / unit / config | CRM Node contract | tested |
| 计划开始、完成、终态拒绝 | UI+command+DB / real-stack / journey / browser | Playwright 已补；runtime 未取得 | did_not_run |
| 记录拒绝任务状态动作 | command / real-stack / integration / HTTP | Playwright 已补；runtime 未取得 | did_not_run |
| 计划/记录独立危险删除 | UI+command+DB / real-stack / journey / browser | DSL、contract、Playwright 已补；runtime 未取得 | partial |
| 线索/联系人/商机 direct+graph 聚合去重 | API+UI / real-stack / integration / HTTP+browser | SQL contract 通过；真栈未跑 | partial |
| 客户跨下级对象聚合去重 | API+UI / real-stack / integration / HTTP+browser | SQL contract 通过；真栈未跑 | partial |
| follow-record pool-list | API / real-stack / integration / HTTP | named-query contract 通过；真栈未跑 | partial |
| 只读角色 UI/direct API 一致拒绝 | permission / real-stack / blocking / browser+HTTP | 未取得 runtime | did_not_run |
| 负责人通知 | event+notification / real-stack / integration / browser+DB | 当前切片未新增可靠的 owner-PID 通知链 | remaining_blocker |
| 评论/@提及/通知回归 | UI+notification / real-stack / journey / browser | 产品代码未改；本轮未重跑 | did_not_run |
| 移动端 | mobile / real-stack / journey / native | 按共同契约保留分母 | untested |

## 3. 已执行证据

- `node --test plugins/crm/tests/*.test.mjs`: `87 pass / 0 fail`。
- `audit-page-ux.mjs ... --lifecycle-detail crm_activity_common_detail`: `3 pages / 7 guided fields / 0 error / 0 warning`。
- `check-dsl-actions.mjs plugins/crm`: PASS。
- `check-command-reachability.mjs`: PASS；输出仅含既有 baselined warnings。
- `check-e2e-spec-registration.mjs`: PASS。
- `pnpm typecheck`: PASS。
- `verify_release_coverage.mjs --self-test-mutation`: green → controlled red → restored green。
- 生成分母：`739 pass / 2754 untested`；该数字是 OSS 产品 manifest 行，不是产品完成率。

## 4. 未执行与环境

workspace runtime 预算已满：两个 development runtime 分别由 T04/T05 占用，一个 verification runtime 由 Quote/BOM 任务占用。因此本轮未申请新 runtime，未运行 import、真实 PostgreSQL named query、浏览器、权限、通知、截图或 fresh-runtime mutation。

这属于 `environment-capacity-unavailable`，不把未运行项目记为 pass，也不删除分母。

## 5. Final Evidence Pack

- SOT: Enterprise `crm-cordys-parity-sot.md`（本 OSS 叶子任务未修改）
- source root: `/Users/ghj/work/auraboot/.worktrees/auraboot-crm-w1-followup-t06-20260823`
- base: `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043`
- runtime: `did_not_run: workspace development 2/2 and verification 1/1 occupied`
- evidence root: `not_created_without_runtime`
- tested: JSON/DSL UX/static contracts/coverage generation/typecheck
- partial: delete semantics, aggregation, pool-list
- API-backed: none claimed without runtime
- did_not_run: real-stack browser/API/DB/permission/notification/screenshots
- remaining_blockers: runtime verification; owner notification implementation and verification
- truth audit: static coverage gate mutation passed; new real-stack assertions have not yet been seen red/green
- allowed claim: `filesystem-and-static-contract-verified`

## 6. Shared JSON Pointers

- `/plugins/crm/config/i18n.json`：仅将旧活动删除 label 局部替换为 `command.crm:delete_follow_plan.label`，并相邻新增 `command.crm:delete_follow_record.label`。
- `/plugins/crm/config/named-queries.json`：修改 `code=crm_activities_by_object`、`code=crm_account_timeline`；新增 `code=crm_follow_record_pool_list`。
- `/plugins/crm/coverage-manifest.json` 与 `/docs/coverage/oss-coverage-manifest.json`：由 canonical 生成器再生；未手工重排。

## 7. 后续验证顺序

1. runtime 容量释放后，以本 worktree 为 source root 申请一个 development runtime。
2. 构建/import CRM，确认 named query、页面和两个新删除命令均来自本 branch。
3. single worker、retry=0 跑 `crm-contact-followup-lifecycle-parity.spec.ts`。
4. 补三对象聚合、pool-list、只读角色和负责人通知的 browser/API/DB 成对证据。
5. 对新状态/删除/去重门禁做产品行为变异，见红后恢复再见绿；随后截图视觉复核。
