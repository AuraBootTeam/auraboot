---
type: plan-design
status: active
created: 2026-06-20
slug: dsl-command-form-action
---
<!-- no-precipitation: design spec; outcomes land in feature code + git history -->

# Design — DSL「命令弹表单」通用积木(`command.execute` + `inputFields`)— 2026-06-20

## 背景与问题

平台 DSL 的命令交互现在只有两档:① 走整张 form 页(create/update);② 无参数按钮(start/pause 这种点了直接执行)。**缺中间一档**:点一个 action 按钮 → 弹一个小对话框填 2-3 个字段(理由 / 数量 / cookies / 过期时间)→ 收集后提交命令。这是非常通用的交互(审批驳回填理由、库存调整填数量+原因、工单转派选人+备注、爬虫账号录入 cookies),但当前没有 DSL 表达,逼着每个场景各写一个一次性 custom 前端组件。

触发场景:2026-06-20 爬虫账号凭据录入(`crawler-account-credential-inUI-entry`)撞到这个缺口。

## 关键实测(决定方案 = 低成本复用)

- **`FormDialog` 已存在**(`app/framework/meta/runtime/actions/FormDialog.tsx`):监听 window 的 `'dialog:form'` CustomEvent,渲染 modal 表单,已支持字段类型 `text / textarea / number / select` + `required` 校验 + i18n + select 的 static/api dataSource。
- **`dialog.form` action 已存在**(`ActionRegistry.ts:640`):dispatch `'dialog:form'` event,`onSubmit` 把值存进 stateManager,返回一个 Promise(resolve on submit / reject on cancel)。
- **`onClick` 执行单个 action**(`ActionRegistry.execute(type, context)`,`ActionRegistry.ts:140`)——没有"一个按钮跑多个 action 序列"的机制。所以"先 dialog.form 再 command.execute"不能用现成的两步拼。
- **结论**:给 `command.execute` 加一个 `inputFields` 语法糖——handler 检测到 `inputFields` 就**复用现成的 `'dialog:form'` event 机制**弹 `FormDialog` 收集,收集后并进 `payload` 再提交。`FormDialog` 零改动。

## 目标 / 范围

**In scope（平台能力 + 一个真实消费场景）:**
1. **平台**:`command.execute` action 支持 `args.inputFields`(+ 可选 `inputFieldsTitle`):点按钮 → 弹 `FormDialog` 收集字段 → 字段值并进 `args.payload` → 提交 `/api/meta/commands/execute/{code}`。用户取消则不提交。
2. **平台**:`ActionConfig` 类型加 `inputFields?: ActionInputField[]` + `inputFieldsTitle?`。后端 `import-directory-sync` validator 放行这个 action args（实测确认；若拒则在 DslRegistry/validator 放行）。
3. **爬虫消费**（验证场景，跨 crawler 仓）:`cr_account:set_credential` 命令 + Java handler（`SpringContextUtil.getBean(StringRedisTemplate)` 拿 Redis 写 `cr:acct:cred:{ref}` 明文 json + cred-meta）+ Python `CredentialStore` 去 Fernet（明文 json，见 `crawler-account-credential-inUI-entry` spec）+ cr_account detail 页 action 带 `inputFields`（cookies `textarea` + expires `number`）。

**Out of scope（defer）:** 新字段类型（datetime/checkbox/upload —— FormDialog 加 case 即可，后续按需）· 复杂校验（min/max/pattern，现仅 required）· action 序列引擎（onClick 多步）· 凭据加密（爬虫侧已决定去加密）。

## 数据契约（`inputFields` 形状 = 复用现有 `FormFieldConfig`）

`ActionInputField` 沿用 `FormDialog` 的 `FormFieldConfig`，不另造一套:
```ts
interface ActionInputField {
  field: string;                 // 收集后写进 payload[field]
  label?: string | I18nText;
  type?: 'text' | 'textarea' | 'number' | 'select';
  required?: boolean;
  placeholder?: string | I18nText;
  defaultValue?: any;
  dataSource?: { type: 'api' | 'static'; endpoint?: string; data?: {label,value}[] };
}
```
DSL:
```json
{ "action": "command.execute", "args": {
    "command": "cr_account:set_credential", "targetRecordId": "${form.pid}",
    "operationType": "update",
    "inputFieldsTitle": {"zh-CN": "录入凭据"},
    "inputFields": [
      {"field": "cookies_json", "label": {"zh-CN": "Cookies JSON"}, "type": "textarea", "required": true},
      {"field": "expires_at",  "label": {"zh-CN": "过期时间(可选)"}, "type": "number"} ]}}
```

## 架构 — 改动点（单一职责）

1. **`ActionRegistry.ts` 的 `command.execute` handler（改，~10 行）** — 在解析 `args.command` 之后、调 `fetchResult` 之前:`if (args.inputFields?.length) { const collected = await promptInputForm(args.inputFields, args.inputFieldsTitle, fetchResult); args.payload = {...args.payload, ...collected}; }`。用户取消（reject）则整个 action 中止、不提交。
2. **`promptInputForm` helper（新，~25 行，同文件或近邻）** — 复用 `dialog.form` 的逻辑:预取 select 的 api options → dispatch `'dialog:form'` event（`FormDialog` 已监听）→ 返回 Promise，`onSubmit(formData)→resolve(formData)`、`onCancel→reject`。**与 `dialog.form` handler 共享同一抽取出来的内部函数**（DRY:把 `dialog.form` handler 里"预取 options + dispatch event + Promise"那段抽成 `promptInputForm`，两处都用）。
3. **`app/types/schema.ts` `ActionConfig`（改）** — 加 `inputFields?: ActionInputField[]` + `inputFieldsTitle?: string | I18nText`。
4. **`FormDialog.tsx`** — **不改**。
5. **后端 validator** — 实测 `import-directory-sync` 是否拒 `inputFields`;若拒，在 DslRegistry/action-args 放行（仅当需要）。

## 错误处理 / 边界

- 用户取消表单 → reject → command.execute 中止，不提交，不报错（静默取消）。
- `inputFields` 缺失或空 → 走原 `command.execute` 路径（向后兼容,零影响现有命令）。
- required 字段空 → FormDialog 内拦截（现有逻辑），不关闭、不提交。
- 收集的字段 key 与已有 payload key 冲突 → inputFields 收集值覆盖（`{...payload, ...collected}`,文档说明）。

## 测试策略

- **前端单测**（`ActionRegistry` __tests__）:`command.execute` 带 `inputFields` → mock `'dialog:form'` event/promptInputForm → 提交时 payload 含收集的字段值;取消 → 不调 fetchResult。向后兼容:无 inputFields 时行为不变。
- **后端**:`import-directory-sync` 对含 `inputFields` 的 page DSL 返回 `success:true`（host-first golden 里验，或一个 fixture）。
- **Host-first 真浏览器 golden**（§2.2,零 docker,复用账号池 slice-2 隔离栈 recipe）:爬虫 cr_account detail 页点"录入凭据" → 弹表单 → 填 cookies → 提交 → Redis `cr:acct:cred:{ref}` 有 json material + cred-meta → `cr_acct_cred_present` 刷成真 → **detail 不回显 cookie 明文**。这同时验证了平台能力 + 爬虫消费。
- **文档**:`auraboot/docs/system-reference/core/09-DSL能力边界完整参考.md` 加 `command.execute` 的 `inputFields` 说明 + DSL 样例。

## Net effect

平台 DSL 补上"点按钮→弹小表单→提交命令"这一通用积木,复用现成 `FormDialog`/`'dialog:form'` 机制,改动 ~40 行前端 + schema。爬虫账号凭据录入作为首个真实消费场景端到端验证。后续审批/库存/工单等场景可直接 `inputFields` 复用。
