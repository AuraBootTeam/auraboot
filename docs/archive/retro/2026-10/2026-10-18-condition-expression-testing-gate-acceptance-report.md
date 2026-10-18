---
type: retro
status: closed
created: 2026-10-18
distilled_to: ../../../system-reference/web/model-driven-condition-editor.md
---

# 模型驱动条件编辑与回归债务闭环

## 范围与结论

沿用 ConditionBuilder、既有 Condition AST、fact catalog、片段版本 API 和 DecisionRuntime。
模型/属性/类型值、嵌套 AND/OR/NOT、字段比较、保存回读及后端解释已经真实跑通。
本轮进一步关闭原11个前端测试失败、2个类型错误，补充真实只读角色旅程；不声明全产品回归。

唯一稳定契约：`docs/system-reference/web/model-driven-condition-editor.md`（README已有入口）。
过程证据：`/Users/ghj/work/auraboot/.workspace/evidence/acceptance-detail-tabs/runs/2026-10-18T05-36-18-938Z-9d28f2a2-ea6c-4d01-a883-e22227b2a72a`。
最终源码/构件、逐图结论、合并及恢复结果引用该目录 manifest.md / final-receipt.json，不把计划当执行。

## 修复原因

- 示例 tableDrafts 提前填满，导致 listVersions 误以为真实版本已加载：改为显示时使用模板，恢复缓存从空开始。
- 工作台测试仍模拟 evaluate、直接发布和旧默认Tab：改为断言 stateless testRun、当前检查与发布语义，保留 payload/版本/竞态断言。
- WidgetConfig 缺已支持的 ring/showLabel 可选布尔属性：补上类型，维持 visualization 优先级，既有renderer测试覆盖两值。
- 条件片段库菜单和入口仅考虑管理员：菜单改读取权限，各写入口独立鉴权；只读条件复用 readOnly 渲染，不能修改。
- 扩大Dashboard回归发现交付清单用例仍断言直读模型：按现有 tpm_delivery_brief 命名查询检查资源、读取权限、租户限制及输出字段。
- 视觉复核发现只读控件挤在窄侧栏：改为全宽查看；发布禁用状态加明确视觉反馈。

## 执行账本

| 层/入口 | 执行 | 通过 | 失败 | 跳过 | 证据 |
|---|---:|---:|---:|---:|---|
| 前端 decision + smart decision + core-dashboard / vitest | 602 | 602 | 0 | 0 | frontend-final-02.json/log |
| Web全量 tsc --noEmit | 1 | 1 | 0 | 0 | typecheck-final-02.log |
| 浏览器菜单驱动三条主旅程 | 3 | 3 | 0 | 0 | browser-01/condition-browser-results.json |
| 既有后端 AST+Runtime 单元（代码未修改，复用前轮） | 32 | 32 | 0 | 0 | 前轮backend-unit-final.log |

浏览器覆盖：编辑保存/刷新、TRUE/FALSE/UNKNOWN、逐节点解释、字段比较、新旧版本不可变性、无试算日志；
真实只读账号通过BFF登录并校验租户/角色，菜单进入，禁用编辑控件；五种写/求值HTTP请求403，版本内容不变，刷新仍只读；
业务决策表168000阈值回读、188000试算输出加强审批路由、刷新保持原规则。
最终只读全宽和禁用反馈的截图复验见同轮后续 browser 目录，不以旧窄布局截图宣称视觉完成。

执行使用同一 acceptance-detail-tabs / slot30 runtime，数据保留，每轮新建证据目录；不混用宿主服务和构件。
本轮无skip/fixme/retries，不通过API替代主编辑旅程。API仅准备独立规则/角色和独立核验结果。
原字段兼容性mutation的红/恢复绿证据仍有效；不虚构新增覆盖率。

## 边界

- integration coverage 未测量；没有全产品、移动端、读屏或外部部署回归声明。
- DSL静态UX巡检整个旧规则中心17页发现14条既有问题，全部位于未改的pages.json；本轮仅改菜单权限，条件片段页无该巡检错误。
- 不把这14条无关页债务伪装成当前切片失败，也不宣称它们已修复。见 dsl-audit.log 和 pages.json无改动证明。
- PR合并仅在当前任务授权下执行，最终 merge SHA 与本地同步、预览恢复由 closeout receipts 裁决；无生产发布。

## SOT Updates

权限分工、只读完整旅程、模板/服务端恢复优先级及发布前检查契约已萃取到稳定文档。
本过程报告结束并归档；代码/证据及实际合并状态可由固定入口独立核对。
