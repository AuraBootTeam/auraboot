---
type: retro
status: closed
created: 2026-10-15
distilled_to: plugins/project-management/README.md
---

# 独立客户资料 AI 整理入口：配置切片验证

allowed_claim: 配置契约窄验证通过；live browser E2E did_not_run（executed=0）。尚无真实模型提取质量或页面黄金通过声明。

独立菜单“AI整理客户资料”进入 `tpm_customer_ai_intake`，复用既有 `tpm_customer`、通用 `ai-fill-banner`、真实会话 `form_draft_fill` 和 `tpm:create_customer`。未更改默认客户/项目表单，无业务 React、新模型、新命令或 AI 专属接口。页面 blueprint 与持久契约沉淀到插件 README。

```text
acceptance_report: this file
claim_level: targeted-tested (configuration only)
current_sot: plugins/project-management/README.md
business_scope: explicit AI customer intake page/menu; two existing customer fields; existing create command; live-only candidate journey
integration_tests: did_not_run; no runtime operations authorized for this subagent
integration_coverage: coverage_not_measured
e2e_specs: web-admin/tests/e2e/project-management/customer-ai-intake-live.spec.ts
e2e_collection: 1 live-only test collected
e2e_execution: live browser executed=0 did_not_run
config_execution: node tests executed=3 passed=3 failed=0 skipped=0
feature_action_matrix: static menu/permission/page/field/required/command/default-no-AI alignment covered; live extraction/review/undo/manual edit/save/readback remain candidates
browser_evidence: none; screenshot scenarios declared in live spec
backend_evidence: none; actual import and command execution pending root integration runtime
artifact_evidence: JSON parsed and cross-checked with existing bindings and commands; no generated business artifact claim
permission_negative: static menu and command permission alignment only; live permission denial did_not_run
visual_feedback: static page UX audit one page two editable fields 0 errors 0 warnings; original screenshot review did_not_run
skip_fixme_threshold_retry_audit: live spec has no stub directives, network mocks, skips or retries; hard prerequisites fail rather than silently bypass
live_provider_guard: PW_REQUIRE_LIVE_LLM=1 plus exact PW_EXPECT_LIVE_PROVIDER/MODEL; persisted trace metadata.provider_code and generation model must match
remaining_blockers: platform import validator, genuine configured provider, real menu-driven execution and original screenshot review, role-negative scenario
allowed_claim: independent config slice and executable live candidate exist; no live quality/complete/golden pass claim
```

窄验证命令：

```bash
node --test plugins/project-management/tests/customer-ai-intake-config.test.mjs
node /Users/ghj/.codex/skills/aura-dsl/scripts/audit-page-ux.mjs plugins/project-management/config/pages/tpm_customer_ai_intake.json
cd web-admin
PW_PROFILE=full ./node_modules/.bin/playwright test tests/e2e/project-management/customer-ai-intake-live.spec.ts --project=chromium --no-deps --list
```

候选真实旅程覆盖：空白输入不可提交并可取消；脏文本经真实 LLM 提取；核对字段依据；撤销两字段；再次提取，人工确认联系人；保存前客户查询仍为空、业务写入请求为零；显式保存命令 payload 与实际客户一致；刷新后列表和 API 都回显人工确认值。两个提取请求具有不同输入与不同 trace，并校验实际 provider/model，不能以旧 stub 接线测试冒充本场景。

本切片只写独占 worktree 的配置、候选测试和文档。未启动/停止 runtime，未配置密钥，未调用真实 LLM，未发布、推送或合并。
