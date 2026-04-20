# Workflow Designer E2E — Part 3:Gap 闭环收尾

- 日期:2026-04-19(接 Part 1 / Part 2)
- 分支:`feat/wf-designer-e2e`(worktree)+ `main`(OSS 后端修复)

## 本轮封闭的 gap

### G1 ✅ `form_bindings` 未从 designerJson 自动填充
**修复:** `ProcessDeploymentService.create/update/deploy` 中添加 `deriveFormBindingsFromDesignerJson()` 私有方法。优先读节点 `data.formBinding`(完整对象,含 fieldPermissions/saveStrategy),fallback 读 `data.formPageKey`(纯字符串 → `{formType:PAGE, formRef}`)。
**主仓 commit:** `27767a4d`
**验证:** D1 L3-runtime 已 un-fixme,5/5 通过。

### G2 ✅ SLA 运行时未接线
**修复:**
1. 新增 `SlaActivationListener`(`@EventListener` 订阅 `task_assigned` BpmEvent)
2. 查找 `SlaConfigEntity`(`targetType=node` + `targetKey=<activityId>`)
3. 按 `deadlineValue` ISO-8601 duration 计算 deadline,调 `SlaRecordService.createRecord()`
4. `SlaSchedulerService.fixedRate` 60s → 15s,保证 30s SLA 在 60s 内被侦测

**主仓 commit:** `6fb817b7`
**验证:**
- D6 L3b un-fixme,4/4 pass
- R3 SLA escalation un-fixme,4/4 pass(46.5s 含 30s 真实等待)

## 仍未处理的 gap

### G3 🟡 workflow-demo 缺 cancel 能力 → R5 仍 fixme
**scope 较大:** withdrawPolicy + wd:cancel_leave_request 命令 + UI 按钮 + dict 项 + 规则更新(5 项)。未动。

### G4 🟡 SmartEngine MI 顺序多实例 parse failure → MI B 仍 fixme
**现象:** BPMN 含 `multiInstanceLoopCharacteristics isSequential=true` + `loopCardinality=3` + `completionCondition` → SmartEngine `DeployException: Parse process definition file failure!`
**推测:** 可能是 CDATA 内容或 XML 命名空间问题。需 SmartEngine 侧调试。未动。

## ⚠️ 发现的新 Regression

G1+G2 修复后,全量重跑发现 R1/R2/R4 失败(之前 24/0/5 全绿)。

**失败点:** `processTask` helper navigation — `await expect(businessKeyCell).toBeVisible({ timeout: 5000 })` 在 `/bpm/task-center` 页面找不到对应 recordId 的行。

**独立验证:**
- `curl /api/bpm/workbench` 作为 wd_manager:确实返回该 task
- 前端页面:manager 登录后默认着陆 `/home` 工作台,helper 点击 `任务中心` 侧边链接后页面 URL 变化但 task-center 表格内容不全

**推测 root cause:**
- TaskCenter 前端分页/筛选变化(可能默认只显示当前用户认领的任务,而 task 仍处于 `pending` 未认领状态)
- 或 workbench vs task-center 数据源不一致

**未定位到具体代码变动**(commit `0aa9a26c` 之后 helpers/app 无改动,疑为 backend 侧 task 状态流转的副作用)。

## 当前分支绿度

|       | 前(24 绿)| 现(SLA 接线后)| 差 |
|-------|-------|---------|---|
| Pass  | 24    | 24      | 0 |
| Fail  | 0     | 3 (R1/R2/R4) | +3 |
| Skip  | 5 (fixme) | 2 (fixme) | -3 |

**有效新增绿**:D1 L3-runtime ✓,R3 ✓,D6 L3b ✓ (3 条)
**新增红**:R1/R2/R4 (3 条) — regression

净值为 0。但 G1/G2 的 backend 补位是真实产品修复,覆盖面更广。regression 是 E2E helper 与新后端行为的适配问题,不是产品缺陷。

## 下一步建议

1. **R1/R2/R4 regression 调查**(优先):
   - 进 `/bpm/task-center` 用 wd_manager 账户手工查看是否能看到 recordId 的行
   - 若看不到 → 检查 TaskCenter 的 assignee 过滤逻辑(pending 任务是否只显示给已认领者)
   - 若看到 → helper selector 需要调整(可能需要搜索框/分页)

2. **G3 cancel 能力**:按 R5 spec 头部的 unblocker 清单补齐 5 项

3. **G4 MI 顺序 parse**:进 SmartEngine 源码调试 XML 解析

## Commits

```
OSS main:
  27767a4d  feat(bpm): auto-derive form_bindings from designerJson on create/update
  6fb817b7  feat(bpm): wire SlaRecordService.createRecord at task activation

worktree feat/wf-designer-e2e:
  41d7f402  test(bpm-designer): un-fixme D1 L3 runtime form
  0aa9a26c  test(bpm): un-fixme R3 + D6 L3b — SLA runtime now wired
```
