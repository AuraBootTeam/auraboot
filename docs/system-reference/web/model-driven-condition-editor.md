---
type: system-reference
status: active
---

# 模型驱动条件编辑与试算契约

条件编辑复用 `ConditionBuilder` 和既有 Condition AST，不增加页面专用规则格式。
条件片段库、规则绑定等使用方传入事实目录和受控 AST；使用方继续负责版本和保存。

## 元数据与值

模型、属性来自租户权限过滤后的 `/api/decision/facts/catalog`，以 scope、modelCode 和 path 区分字段。
不以示例字段替代真实目录。隐藏、脱敏字段不进入可编辑目录。
运算符随 dataType 变化；数字保存为 number，字典显示标签、保存 code。
比较值可选固定值或另一字段；字段比较要求 dataType 一致，并保持字典/引用域兼容。
这表示比较已提供上下文中的两个字段，不隐含查询关联记录；调用方负责提供真实值。

## 组合与修改

AND、OR 使用 GroupNode；NOT 使用 NotNode，括号就是 AST 层级。
勾选同层连续项可加括号，不能跨父组或跳项包裹；撤销/重做恢复完整 AST。
移除括号须确认保留子条件还是删除整组；取消不修改 AST。
只读调用方通过 readOnly 禁止修改，包括结构、字段和值；服务端权限仍独立裁决。
条件片段库菜单要求 decision.definition.read；编辑/校验要求 decision.definition.manage，
发布要求 decision.definition.publish，求值要求 decision.runtime.evaluate。
只读角色可查看全宽组合条件，不能创建、编辑、校验、发布或求值；刷新后限制不变。

## 保存与版本

条件片段的草稿通过既有 draft API 修改，修改后重新验证；已发布版本必须创建新版本。
保存与刷新后应保持 AST、数字类型、字典 code 和字段引用一致，不把试算上下文写入规则。

## 权威试算

浏览器向既有 `/api/decision/test-run` 提交 SIMPLE_CONDITION、AST_EVALUATOR、当前 AST、
显式输入 context 和 explain=true。服务端先验证配置，非法条件返回 ERROR 和问题列表。
结果来自 ConditionAstEvaluator，经 DecisionRuntime 返回；前端不以本地求值替代。

显式 explain 时 DecisionResult 可附 conditionTrace：逐叶实际左右值、TRUE/FALSE/UNKNOWN、
unknownReasons 和 nodeResults。节点标识为 root、root.0 等 AST 路径；NOT 子项增加 .0。
普通不带 explain 的求值不返回这份输入值解释。
缺失的二元比较操作数为 UNKNOWN，NOT UNKNOWN 仍为 UNKNOWN；只有 TRUE 算命中。
编辑 AST 或输入后旧结果立即失效，迟到响应不能覆盖新的编辑状态。

试算不发布、不执行动作、不写执行日志。发布、实际求值审计与业务动作仍走原有治理入口。
试算使用既有登录/权限检查；解释含本次显式输入值，不落普通执行日志。

## 验证边界

浏览器旅程覆盖菜单进入、真实元数据、组合编辑、保存回读、三态与解释、草稿新版本。
真实只读账号从菜单查看嵌套条件，控件禁用；伪造修改、新建版本、校验、发布和试算请求均403，版本内容不变。
非连续包裹和迟到响应隔离另由组件单测裁决，不冒充实际浏览器穷举验证。
策略工作台的示例表是显示默认值，不能当成服务端已加载版本；服务端恢复优先，迟到恢复不得覆盖本地编辑。
发布按钮在当前草稿校验和影响检查完成前禁用；试算调用无持久化的 test-run，不调用正式 evaluate。
