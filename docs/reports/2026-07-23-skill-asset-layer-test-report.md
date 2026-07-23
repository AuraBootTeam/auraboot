---
type: retro
status: active
created: 2026-07-23
---

# 测试报告 — 数字员工 skill 资产层(OSS #1440 / #1441)

> 范围:**skill 资产层这一个切片**——bound skill 给数字员工贡献受治理 DSL 工具,并用它做真实工作。**不是** agent 成熟度 M1–M20 全面实现(那是另一个大工程,见文末「不在本报告范围」)。
> 结论口径按 `docs/standards/core/testing-layering.md`:每条结论落到一行 + 证据路径。**固定集** = 可重跑自动化;**黄金集** = 再加「见过红」(变异验证)。

## 1. 覆盖矩阵

| # | 被测面 | 轴(UT/IT/E2E) | 证据 | 变异验证 | 判定 |
|---|---|---|---|---|---|
| T1 | `parseToolCodes`(jsonb 解析) | UT hermetic | `AgentSkillServiceJsonbTest`(5/5) | — | ✅ PASS(既有,构造器已适配) |
| T2 | **Gap B**:`loadSkill` 在租户上下文找到 system 内建 skill(绕租户拦截器) | **IT 真栈** | `SkillAssetLayerTenantIntegrationTest#loadSkill_findsSystemBuiltinFromTenantContext` | ✅ 还原 `selectByQueryWithoutTenant→selectByQuery` ⇒ **红** | ✅ **固定+黄金** |
| T3 | **Gap A+B**:`resolveSkillTools` 经 provider registry 解析 `list:crm_account` | **IT 真栈** | `SkillAssetLayerTenantIntegrationTest#resolveSkillTools_resolvesGovernedDslListTool` | ✅ 跳过 registry 解析 ⇒ T3 红、T2 绿(隔离两个 gap) | ✅ **固定+黄金** |
| T4 | 端到端(API):数字员工经 `/chat/stream` 用 bound skill 工具读真客户出复盘 | 真栈 E2E(live qwen) | SSE 输出含 `list:crm_account`;`ab_gen_ai_usage` = `qianwen\|qwen-plus` 两条;复盘含 8 客户/行业/评级 | 一次性(非 rerunnable) | ✅ 已证(手工) |
| T5 | **端到端(浏览器 UI)**:真浏览器对话页 → 发复盘 → agent 回复 + 工具输出渲染 | 真栈 UI E2E(live qwen) | `digital-employee-skill-review.spec.ts`(1 passed,14s);截图 `test-results/digital-employee/skill-review-ui.png`(本地 `/tmp/de-skill-review-ui.png`) | 断言用 role marker(非页面文本)+ grounded「8」 | ✅ **通过 + agent-vision 视觉确认** |

**门禁挂载**:T2/T3 已注册进 `scripts/dev/run-agent-runtime-backend-gate.sh`(长期回归)。T5 是 web-admin E2E,随数字员工对话页(现为手写 React `core-ai-colleagues`,§7 债)一起验;DSL 化后再 pin 进 golden runner。

## 2. 变异验证明细(黄金集的「见过红」)

在跑过一轮的真栈上做,非空断言:

- **变异 A**(`loadSkill` selectByQueryWithoutTenant → selectByQuery):`failures="2"` —— T2、T3 **都红**(loadSkill 返 null → 一切为空)。
- **变异 B**(`resolveToolCodes` 跳过 provider-registry 解析):`failures="1"` —— T3 **红**、T2 **绿**(loadSkill 仍工作;两个断言正确隔离两个 gap)。
- 还原后复跑:`tests="2" failures="0"`。

## 3. UI 端到端视觉确认(T5)

截图显示:头部「客户运营助理·小奥·严谨的客户运营数字员工」;回复渲染**关键洞察**(软件行业集中度、A=C 级建议提升 C 级、地域覆盖)+ **建议行动**;并有 **「Data Query · 8 records」卡**渲染真实 `crm_account` 列(industry/name/rating…软件/农业/传媒 A/C/C…)= 受治理工具 `list:crm_account` 的输出真进了 UI。零编造、只读。

## 4. 诚实缺口

- **T4 未做成 rerunnable 自动化**:live qwen 端到端目前是手工一次性证明;真栈自动化 golden(带 LLM key)与 CI 解耦,是后续(见 `docs/backlog/2026-06-12-agent-testing-strategy-and-eval-loop.md` 的五层策略)。T2/T3(stub 无 LLM)已覆盖工具接线这层机制。
- **T5 未 pin 进长期 golden**:数字员工对话页是手写 React(§7 债,DSL 化另起);pin 前等 UI 收敛。
- **调试副作用**:de-live2 admin 密码在本会话被我重置(最终对齐 E2E 期望值 `Test2026x`)——测试库,非生产。

## 5. 不在本报告范围(诚实)

原始「agent 成熟度全面实现 / 全面解决所有 gap」= M1–M20 + DDR §5 决定 2-4(办公连接器 / 调研 / proactive 履职)。**本切片只交付并验证了 skill 资产层这一条**;其余是独立工程,未开工。

## 证据路径(绝对)

- IT:`/Users/ghj/work/auraboot/.worktrees/oss-de-skill/platform/src/test/java/com/auraboot/framework/agent/service/SkillAssetLayerTenantIntegrationTest.java`
- UI spec:`/Users/ghj/work/auraboot/.worktrees/oss-de-skill/web-admin/tests/e2e/agent-control-plane/digital-employee-skill-review.spec.ts`
- UI 截图:`/tmp/de-skill-review-ui.png`
- 修复(已 merge main):OSS #1440;测试:OSS #1441
- 决策 + live 结果:`auraboot-enterprise` DDR-2026-07-23 §9(ENT #884)
