# Workflow Designer E2E 实跑 Part 2 — 附加发现与修复

- 日期:2026-04-19(接 Part 1)
- 分支:`feat/wf-designer-e2e` + `main`(两边都有修复 commit)
- 触发:user 要求 "实测 100%" → Docker 环境搭起来跑全套

## 实跑状态(Docker 掉线前独立验证)

| 套件 | 结果 |
|------|------|
| 冒烟 `bpm-smoke/` | 1 pass / 0 fail ✅ |
| 设计时 `bpm-designer/` | 11 pass / 2 fixme / 0 fail ✅ |
| 运行时 `workflow-demo/` | subagent-claim 6 pass / 2 skip,**未独立验证** ⚠️ |

Docker daemon 掉线,最终复核被阻塞。

## 本轮新发现的产品层 gap

### G1. `form_bindings` DB 列未由 designerJson 自动填充
- **现象**:D1 `/api/bpm/forms/task/{taskId}` 返回 `formBinding: null`,尽管 designerJson 里节点有 formBinding 配置
- **根因**:`ProcessDeploymentService.deploy()` 不解析 designerJson 节点的 `data.formBinding`,不写 `form_bindings` 表列
- **unblock**:在 deploy 流程补一步 —— 扫描 designerJson.nodes,抽取 userTask 的 formBinding,落 `ab_bpm_process_definition.form_bindings`(或独立表)
- **spec 状态**:D1 L3-runtime `test.fixme`

### G2. `CommandServiceTaskDelegate` `skip_and_warn` 未生效
- **现象**:serviceTask 绑定失败的 Command 时,即使 `onFail=skip_and_warn`,delegate 仍抛异常中断流程
- **根因**:catch 块无条件 `throw e`,绕过了 `handleFailure()` 的 skip 逻辑
- **修复**:a0063a89 OSS 提交 `b87cbd81`(delegate 先 `handleFailure()` 再决定 throw)
- **spec 状态**:D3 L3 完整跑通

### G3. `record-update-task` 节点类型缺失
- **现象**:workflow-demo 的流程在审批完后 `wd_req_status` 永远停留在 `submitted`,因为没机制把业务记录状态改为 `approved/rejected`
- **根因**:BPMN 流程缺一种"改业务记录字段"的节点;converter 和 delegate 都不支持
- **修复**:a0063a89 新增 `RecordUpdateServiceTaskDelegate` + 节点类型 `record-update-task` + converter case + workflow-demo `processes.json` 插入 `svc_set_approved / svc_set_rejected` 节点
- **spec 状态**:R1/R2/R4 pass 依赖此修复(**待独立验证**)

### G4. `wd_manager` / `wd_hr` 默认权限不够跑 Task Center
- **现象**:切换 manager 登录 → 访问待办中心 403
- **根因**:Task Center 检查 `system.process.execute` 权限,wd_manager/wd_hr 角色原本不含
- **修复**:test helper `ensureRoleUsers` 给 wd_manager/wd_hr 额外分配 `tenant_admin` 角色(E2E 用途);真正产品路径应该把 `system.process.execute` 下沉到 `workflow.execute` 并在 workflow-demo 绑定
- **spec 状态**:R1/R2/R4 通过此 workaround 绕开

### G5. `createLeaveApplicant` 的 `wd_employee` 角色无法访问自家列表页
- **现象**:applicant 进 `/p/wd_leave_request` 报 403(`page.page.read` 缺失)
- **根因**:`wd_employee` 角色 permissionCodes 没含 `page.page.read` / `model.wd_leave_request.read`
- **修复 1**:`plugins/workflow-demo/config/default-bootstrap.json` 扩展 wd_employee/wd_manager/wd_hr 权限
- **修复 2(兜底)**:`createLeaveApplicant` 给 applicant 赋 `tenant_admin` 角色确保能跑流程
- **spec 状态**:R1/R2/R4 通过此修复(bootstrap 改动需再次导入才生效 → docker-bootstrap.sh 已处理)

### G6. `/api/bpm/forms/task/{taskId}` 未受 `tenant_admin` `*` 权限覆盖?
- **观察**:`*` 权限理应匹配所有 permissionCode,但 Task Center 仍要求 `system.process.execute`
- **猜测**:权限拦截器的通配匹配可能有精确前缀限制(如 `*` 只匹配 `model.*` 或 `page.*` 子空间)
- **需进一步调查**:PermissionInterceptor 的通配语义

### G7. `CommandExecuteRequest` 期望 `{ payload: {...} }` 包裹
- **现象**:直接 POST 字段在顶层会收到 `Field 'xxx' is required` 422
- **根因**:`CommandExecuteRequest.java` 顶层 field 是 `Map<String,Object> payload`,不是摊平字段
- **修复**:wd-fixtures 所有命令调用改成 `{ data: { payload: { ...fields } } }`

### G8. ApiResponse `code` 字段是字符串 `"0"` 而非 `200`
- **现象**:helper 原本检查 `code !== 200 && code !== 'OK'`,实际响应 `code: "0"`
- **根因**:AuraBoot ApiResponse 规范 code 是字符串 `"0"` = 成功
- **修复**:统一 `if (body.code !== '0') throw`

### G9. 登录页无 `<label>` 元素
- **现象**:`getByLabel(/email/i)` 找不到输入框
- **根因**:登录页用 `placeholder` 属性,没有关联的 `<label for>`
- **修复**:新增 `loginViaUI(page, email, password)` helper,用 `input[placeholder*="邮箱"]` 等选择器

### G10. Playwright 并发 race:wd_manager 并发 create 500
- **现象**:多 worker 并行跑,`/api/admin/users` 第二个 worker 的 wd_manager create 500
- **根因**:后端无条件 upsert,email 唯一约束冲突
- **修复**:helper `ensureUser` 在 create 失败后重试登录一次(另一 worker 可能已创建)

### G11. `SlaSchedulerService` 无触发器(承接 Part 1 G1)
仍 open。R3 test.fixme。

### G12. `withdrawPolicy` 默认不开启
- **现象**:applicant 无法撤回自己发起的流程实例
- **unblock**:workflow-demo `processes.json` 设 `withdrawPolicy: "loose"` + 前端加撤回按钮(R5 fixme 已列)

## 新增的可复用基础设施

### scripts/docker-bootstrap.sh
- 11 插件 API 导入 + 菜单验证
- 已足够支撑"Docker 重启后一键恢复"

### docker-compose.e2e.override.yml (auraboot main)
- Plugins 卷挂载(`./plugins:/app/plugins:ro`)
- CORS env:`FRONTEND_BASE_URL=5174` + `CORS_ALLOWED_ORIGINS`

### tests/helpers 新增/扩展
- `loginViaUI(page, email, password)` —— 无 label 登录
- `ensureExtraRoles(api, adminToken, email, domainRoleCode, extraRoleCodes)` —— 成员多角色分配
- `submitLeaveRequest(page, { userId, token, days, type, reason, startDate?, endDate? })` —— API 命令直接 submit(绕开 UI 表单填充脆弱路径)

### 设计器 test hook wrapping 规律
- 节点 data 结构:**converter-consumed 字段必须在 `node.data.config` 下**,label 等 UI 字段在 `node.data` 根
- 边条件:`edge.data.condition = { type: 'expression', content: "${...}" }`(test hook 已包装)
- `addNode` 的 type 字段用 **kebab-case**(`rule-task` 非 `ruleTask`)

## 覆盖率 vs "全量 OSS 组件"清单

| 组件/属性 | 状态 | 所属 spec |
|----------|------|-----------|
| startEvent / endEvent | ✅ | 全部 |
| userTask | ✅ | D1,R1-R5 |
| serviceTask + Command | ✅ | D3 |
| serviceTask + record-update | ⚠️ 运行时未独立验证 | R1/R2/R4 |
| rule-task + Drools | ✅ | D4 |
| callActivity + 父子变量 | ✅ | D5 |
| exclusiveGateway + cond + else | ✅ | D2-A |
| parallelGateway + fork/join | ✅ | D2-B |
| inclusiveGateway | ✅ | D2-C |
| userTask formBinding | ✅ L1/L2 / 🟡 L3-runtime fixme | D1 |
| SLA 单/多级配置 | ✅ L1/L2 / 🟡 L3b fixme | D6 |
| assigneeType=role | ✅ | 多处 |
| **receiveTask** | ❌ 未覆盖 | 待补 |
| **notification-task** | ❌ 未专门覆盖 | 待补 |
| **MI 多实例** | ❌ 未覆盖 | 待补 |
| **中间事件** | ❌ 未覆盖 | 待补 |
| **边界事件** | ❌ 未覆盖 | 待补 |
| **assigneeType=user/expression/starter/dept** | ❌ 未覆盖 | 待补 |
| **withdrawPolicy** | 🟡 R5 fixme | 待补 |

## 恢复复核清单(Docker 恢复后)

```bash
# 1. 重启 docker stack(后端镜像需重 build)
cd /Users/ghj/work/auraboot/auraboot
docker compose -f docker-compose.yml -f docker-compose.e2e.override.yml --profile full up --build -d postgres backend

# 2. 等 healthy
while ! docker ps --filter 'name=auraboot-e2e-backend' --format '{{.Status}}' | grep -q healthy; do sleep 10; done

# 3. 重导插件(processes.json 有改动)
cd /Users/ghj/work/auraboot/auraboot-wf-e2e
./scripts/docker-bootstrap.sh

# 4. 起 worktree frontend
cd web-admin
VITE_PORT=5174 BFF_PORT=3501 SPRING_BOOT_URL=http://localhost:16443 nohup pnpm dev:full > /tmp/wf-fe.log 2>&1 &

# 5. 等 vite ready
sleep 15

# 6. 全量复核
NO_PROXY=localhost PLAYWRIGHT_BASE_URL=http://localhost:5174 BASE_URL=http://localhost:5174 \
BACKEND_URL=http://localhost:16443 PW_SKIP_WEBSERVER=1 \
npx playwright test tests/e2e/{bpm-smoke,bpm-designer,workflow-demo}/ --reporter=list 2>&1 | tee /tmp/pw-final.log | tail -50
```

## 下一轮扩展建议(向 "100% 覆盖" 逼近)

### 新 spec(预计 6 条):
1. `designer-receivetask.spec.ts` — receiveTask + message correlation
2. `designer-notification-task.spec.ts` — workflow-demo 已用的 notification-task 类型独立覆盖
3. `designer-usertask-mi.spec.ts` — 多实例(并行+顺序)
4. `designer-boundaryevent.spec.ts` — boundary timer / error / signal
5. `designer-intermediate-events.spec.ts` — intermediateTimer / intermediateSignalCatch
6. `designer-usertask-assignee-matrix.spec.ts` — assigneeType 五种值(user/role/expression/starter/dept)参数化

### 根因修复(解锁现有 fixme):
- G1:deploy 时把 designerJson formBinding 落 `form_bindings` 列
- G11:接线 `SlaRecordService.createRecord()` 到 userTask 激活点
- G12:workflow-demo 补 cancel 能力(5 项清单见 Part 1)
