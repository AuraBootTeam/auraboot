---
type: system-reference
status: active
updated: 2026-10-30
---

# 插件定制保护与字段删除准入

本文定义 Page、Dashboard 的插件重导入保护，以及普通字段删除的保留数据边界。架构审查、需求和过程账本位于 Enterprise 的 `platform-business-system-foundation.md` / `2026-10-30-business-foundation-journeys.md`。本文不定义通用三方合并、破坏性迁移或自动回退。

## 安全覆盖

| 策略 | 已存在资源 | 保护状态读取 |
| --- | --- | --- |
| ERROR | 拒绝冲突 | 无需读取标记 |
| SKIP | 保留内容、归属、基线和标记 | 无需读取标记 |
| OVERWRITE_SAFE | 只更新已跟踪且未修改的资源；修改过或未跟踪的既有内容跳过 | 必须成功读到已跟踪记录的非空标记；否则失败 |
| OVERWRITE | 操作者明确选择替换 | 不以修改标记作为阻断条件；其他权限/校验继续有效 |

Page 使用 `ResourceType.PAGE/page`，Dashboard 使用 `ResourceType.DASHBOARD/dashboard`，两者的同名资源互不混用。资源类型及导入次序由 `ResourceType` 维护，Dashboard 的物理表为 `ab_dashboard`。

用户保存 Page、更新/发布/取消发布 Dashboard 时，Controller 事务包含修改标记与内容操作。`PluginResourceTracker` 使用小写数据库 code、行锁和精确一行写入判定；标记查询/写入失败会中止该事务。插件导入调用 service，不经过这些用户 Controller。

导入沿用现有程序化事务和资源行锁。`PluginImportSupport` 统一读取保护状态；不得把存储错误或未知标记解释成未修改。锁定查询使用独立的强类型状态投影，显式读取 `user_modified`，避免缺失列或 NULL 被实体的 Java 默认 false 掩盖。SKIP 不写入资源跟踪基线，尤其不能清空旧 `user_modified`，否则第二次安全导入会失去保护。

自动 stub 是有限例外：只有 `auto_created=true` 且 `rowVersion=1` 的未编辑 stub 允许标准定义接替。通过页面 service 保存会移除 stub 标记；历史编辑后的 stub 不再仅凭标记绕过保护。

## 既有安装升级

两个新增 Flyway migration 负责旧记录转换：

- `V20261030113000` 按 Dashboard 的真实 PID 和 tenant 转换旧 `page` 跟踪记录；不能把真正的 Page 跟踪记录错转成 Dashboard。
- `V20261030123000` 对旧 Page/Dashboard 记录保守设置修改标记。旧用户保存曾使用大写资源名，false 不足以证明“用户没有修改过”。

**升级影响**：旧页面和看板在安全导入下可能保留，即使实际上未修改；需检查差异后显式选择 OVERWRITE。新安装/新导入在此迁移后正常形成未修改基线，后续安全升级可更新。迁移不修改页面/看板内容，不自动发布，也不需要用户重新创建页面。

## 普通字段删除

物理删除入口 `SchemaManagementServiceImpl.removeFieldFromModel` 复用 `FieldDeletionGuard`：

1. 主键/系统字段拒绝删除；检查物理对象存在与标识合法性。
2. 在事务中锁定物理表，随后检查共享列绑定、所有租户行的非空值及已保存定义的字段引用。
3. 页面引用索引和实际页面、查询、命令、规则/条件及其版本、事件策略版本、自动化、Dashboard、Report/模板及字段定义参与检查。不能只依赖可能过期的引用缓存。
4. 检查失败、结果未知或有依赖时拒绝；全部满足后才 DROP。物理表锁持有到事务结束，阻止检查与删除之间插入数据。锁等待上限为三秒，超时走失败结果。
5. PID 与数字 ID 的解绑入口复用同一链路；`ModelFieldBindingSupport` 必须核对 SchemaOperationResult 成功后才删绑定。失败结果不会被当作解绑成功。

`refuseIfDataExists=false` 不是破坏性销毁授权，不能绕过公共入口的非空数据保护。跨租户共享物理列存在其他有效绑定时，即使表为空也拒绝删除。若同一物理表由已进入 active/shadow 应用绑定租户的插件模型持有，普通删除同样拒绝，避免绕过现有不可变应用与受控迁移机制；这一保护按插件模型保守裁决，不尝试推断文件 bundle 内的任意引用。字段库删除还需检查有效绑定和定义引用，防止删除元数据后留下失效配置。按模型移除字段时，若其他模型仍在复用该字段定义，则保留定义；解绑失败不得继续删除字段定义。平台/引擎拥有的 `ab_*`、`se_*` 表及 metadata-only 模型不允许通过普通模型字段操作执行物理删除。

### 引用检查的边界

这是保守的删除准入，不是新的业务依赖图引擎：复用现有引用表和平台定义存储，对完整字段标识、物理列名与已登记绑定 alias 的 token 做检查，保留版本也计入保护。描述或保留定义出现同名字段时可能要求先人工核查、清理过期声明；不自动删除引用、不扩大到任意 Java/外部服务代码的静态证明。跨租户检查只返回通用拒绝原因，不泄露其他租户的名称或内容。

物理 Schema 与用户字段名不兼容的迁移、有数据的列收缩、外部代码依赖等，应走单独审阅的数据迁移流程；普通删除接口不承担这些生命周期。缺表/缺列的原有幂等行为不等于能恢复已经缺失的数据。

## 代码与测试入口

- 插件：`PluginImportSupport`、`PluginPageNavigationImporter`、`PluginImportServiceImpl.saveOrUpdatePluginResource`、`PluginResourceTracker`、`PluginResourceMapper`。
- 字段：`FieldDeletionGuard`、`SchemaManagementServiceImpl`、`ModelFieldBindingSupport`、`MetaFieldServiceImpl`。
- Hermetic：`PluginCustomizationProtectionTest`、`FieldDeletionGuardTest`、`ModelFieldUnbindProtectionTest` 及既有 Schema/生成器/字段版本回归。
- 真实 PostgreSQL：`:platform-app:integrationTest --tests '*CustomizationProtectionPostgresIT'`。必须由受管 runtime 注入 DATABASE/POSTGRES 变量，缺前提即失败，不静默跳过；fixture 使用该隔离库的真实 Flyway schema。

PostgreSQL 用例是组件集成验证，实际执行 mappers、导入器、Dashboard service/Controller、资源持久化和 Schema service；无关版本历史服务及模型解析使用测试夹具。不冒充完整 HTTP 权限或浏览器旅程，也不以此声称全部平台已通过验证。原始执行、反向对照和覆盖范围随本轮提交保存在 Workspace 任务证据中。
