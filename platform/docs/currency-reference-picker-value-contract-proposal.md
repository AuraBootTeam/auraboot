# 提案：reference 字段 UI picker 提交值契约（以采购/财务币种字段为案例）

状态：提案（proposal，未实现）。本文只登记问题、候选方案与推荐项，不改变任何运行语义。

## 1. 背景

平台 reference 字段的 UI 表单 picker 与字段声明的取值语义存在平台级裂缝：

- **字段声明侧**：采购/财务共 22 个币种引用字段（finance 12 + procurement 10，`*_currency_code` / `*_base_currency_code`）**有意**按 display-key（ISO 代码，如 `CNY`）存储，`refTarget` 声明为 `targetModel: "fin_currency"` + `targetField: "fin_cur_code"`，且**不**声明 `valueField: "pid"`。这是 plugins 仓 PR #649 的审计结论（61 个 refTarget 字段证据表）：币种类被显式标记为 "kept display-key"，与其余 40 个 pid-consumed 字段（已补 `valueField: "pid"` 声明）相对。
- **财务消费侧**：`finance` 插件按代码比较冻结会计策略：`FinanceAccuracyPolicy.BASE_CURRENCY = "CNY"`；`GenerateJournalFromPurchaseHandler` 读取 `pr_rcpt_currency_code` / `pr_rcpt_base_currency_code` 后做 `BASE_CURRENCY.equals(currency)` 判断；`CreateApFromReceiptHandler` 读取 `fin_si_currency` 并写入 `fin_apt_base_currency_code = "CNY"`。若列里存的是 fin_currency 行 **pid**，这些比较与写入直接断裂（pid ≠ "CNY"），且属静默语义污染而非报错。
- **平台 UI 侧（冲突点）**：表单渲染器对动态模型 reference picker **硬编码** `valueField: 'pid'`（`web-admin/app/framework/meta/rendering/RuntimeFieldRenderer.tsx` 与 `ControlledFieldRenderer.tsx` 的动态模型分支），字段声明里的 `refTarget.valueField` 在表单/picker 提交链路上**不被消费**；`DataSourceManager` 按该 valueField 生成选项值（`value: item[valueField]`），因此 picker 对币种字段提交的是 **pid**。声明侧语义（display-key）与提交侧行为（pid）冲突。

## 2. 为什么现在没炸

核心写校验已做双查找（platform f13367c04 "reference validation resolves by pid OR display key"）：候选值命中声明 valueField、pid 列或 display key 任一即放行。因此 picker 提交 pid 也能过校验入库——校验层无法拦截这个语义错位，只能靠 finance handler 在运行期（记账/应付生成时）才暴露，属于最贵的暴露位置。

（列表/子表渲染侧反而已尊重声明：`ListPageContent.tsx` 与 `SubTableViewer.tsx` 按 `refTarget.valueField || refTarget.targetValueField || refTarget.idField || 'pid'` 解析回显；同一声明只有表单提交链路不认。）

## 3. 候选方案

### 方案一（推荐）：平台表单链路尊重 `refTarget.valueField` 声明 + 币种字段显式声明 code 语义

- 平台：`RuntimeFieldRenderer` / `ControlledFieldRenderer` 动态模型分支改为 `valueField: refTarget?.valueField ?? 'pid'`（缺省不变，向后兼容）；`DataSourceManager` 无需改动。
- 插件：22 个币种字段各补 `"valueField": "fin_cur_code"` 声明（与 PR #649 给 40 个 pid 字段补 `valueField: "pid"` 完全同构，复用其 `ref-target-contract.test.mjs` 契约测试模式钉死双向不漂移）。
- 优点：声明成为唯一事实源，一处修复覆盖全部 display-key 引用（币种之外未来的国家/币种/枚举型模型同享）；列表侧语义本来就读声明，平台内聚；未声明字段零行为变化。
- 代价：一次跨仓协作（platform 渲染器 + plugins 22 个字段声明 + 契约测试），并需回归既有 61 字段契约套件。

### 方案二：平台按 `targetField` 自动推导提交语义

picker 无条件提交 `refTarget.targetField` 的值（display key）。否决：翻转所有未声明字段的默认行为，直接影响 PR #649 之外大量按 pid 消费的存量引用，回归面不可控。

### 方案三：维持现状，仅靠核心校验按声明收紧

对已声明 `targetField` 且未声明 `valueField: pid` 的字段强制校验 display-key。否决：只挡不修——picker 依旧提交 pid，用户在表单保存时被拒，体验断裂；且双查找是 f13367c04 刚建立的 API 兼容层，反向收紧会破坏现有 API 客户端。

### 方案四：数据迁 pid，finance 改为逐行 join fin_currency 解析代码

否决：改动最大，破坏冻结会计策略（`FrozenPolicy` 哈希与审计语义均以 code 比较），迁移收益为零。

## 4. 影响面（方案一落地清单，供排期）

| 层 | 改动 | 量级 |
| --- | --- | --- |
| web-admin | `RuntimeFieldRenderer.tsx` / `ControlledFieldRenderer.tsx` 动态模型分支读取 `refTarget.valueField`（默认仍 `'pid'`） | 2 处 |
| plugins/finance | 12 个 `fin_*currency*` 字段声明 `valueField: "fin_cur_code"` | 12 个 field json |
| plugins/procurement | 10 个 `pr_*_currency*_code` 字段同上 | 10 个 field json |
| plugins 契约测试 | `ref-target-contract.test.mjs` 增加 display-key 类的 `valueField` 断言 | 1 处 |
| 回归 | 币种 picker 保存 → `pr_rcpt_currency_code` 存 `CNY` → finance 记账链路 API 实测 | 1 条真栈旅程 |

风险与缓解：valueField 指向的列必须唯一（`fin_cur_code` 为币种模型业务键，PR #649 已断言 targetField 存在性）；存量已误存 pid 的行需要一次数据订正核查（仅 slot 级 runtime 库，无生产数据）。

## 5. 结论

推荐方案一：**"picker 按 `refTarget.valueField` 语义提交"与"币种字段显式声明 code 语义"合并落地**——平台机制负责让声明生效，插件声明负责表达业务语义。二者缺一都会把裂缝留在原地（只改平台则币种字段仍默认 pid；只改插件声明则表单链路不读声明）。本提案不附带实现。
