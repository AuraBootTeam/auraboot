---
type: system-reference
status: active
updated: 2026-10-02
---

# 数据范围与记录权限诊断

## 范围契约

`DataScopeType` 的合法数据库值为 `none/self/dept/team/dept_and_sub/all`。`own` 不是独立枚举，`share` 是记录共享关系，不是数据范围。

单项范围写入必须提供合法值；角色默认范围允许 `null` 清除默认，空字符串和未知值均拒绝。写入非法值返回 `BadParam`（HTTP 400），校验发生在持久化之前。已有坏数据的读取必须失败，禁止把未知值转换为 `all`。

角色默认范围更新和应用到当前授权在同一事务内完成。清除默认不会删除已有的逐项范围；默认值与现有授权的实际范围是不同事实，UI 不应仅凭默认值宣称实际范围一致。

`self` 使用模型 `extension.dataScope.ownerField`（默认 `created_by`），数值列与用户内部 ID 比较，字符串列与用户 PID 比较。`team` 必须声明 `teamField`；缺字段时拒绝，无团队成员关系时普通评估按现有实现退到 `self`。历史评估采用更严格的拒绝规则。多角色范围沿用现有优先级及 `MAX/MIN`，不能据此假设部门和团队在集合上互相包含。

## 管理接口的记录解释

`GET /api/permissions/matrix/explain` 受 `meta.permission.manage` 管理权限约束。

- `memberId` 是当前租户的有效租户成员 ID，不是用户 ID；不存在、已删除、停用、异租户或绑定用户停用时拒绝。
- 有 `recordPid` 时，先通过 `DynamicDataService.getById` 以调用者身份读取该记录，保留调用者的记录及字段授权。调用者读不到时拒绝，不绕过授权，也不降级为空记录评估。
- 然后用目标成员的用户 ID、用户 PID、角色与成员身份，运行统一的 `canOperate` 流水线。保留环境 ID，排除调用者的 party/session 身份。成功或异常均恢复调用者上下文。
- 不提供 `recordPid` 时为无记录评估，不能用它证明某条记录、某个页签或某个字段可访问。
- 诊断没有扩大调用者可见字段。需要敏感字段的策略仍受调用者可读数据限制；它不是凭空恢复隐藏字段的审计通道。

旧 `PermissionEvaluator.explain(..., Long recordId)` 仍是独立的旧接口，不具备加载目标记录的能力。企业治理接口如果调用该接口，不能宣称已经获得真实记录裁决；本次管理矩阵接口已改走记录诊断服务。

## 上传后读取的边界

文件上传、读取和关系管理是不同动作权限。读取还检查文件与业务记录的关系：无关系时只允许上传者；有关联时逐项验证关联记录的 `read` 权限。文件上传成功不自动授予关联记录、报价输出页签或敏感字段权限。

报价上传后不可查看，应记录具体拒绝发生在文件请求、报价主记录请求、页签能力、字段过滤还是命令结果读取。不得仅因为管理员可读，就认为普通角色流程正确。

## 验证入口

- `DataScopeServiceImplTest`：非法范围、非法合并策略、坏数据拒绝，以及现有 self/dept/team 合并。
- `RoleServiceImplTest`：非法默认范围在更新前拒绝。
- `PermissionExplanationServiceImplTest`：实际记录传递、目标成员身份、跨租户拒绝、读取失败拒绝、异常恢复。
- `PermissionMatrixPolicyControllerIT`：真实 HTTP 400 与数据库未写入、team 默认范围持久化、未知成员拒绝。
- `FileUploadControllerTest`：上传能力与读取能力分离、无关系上传者约束、关联记录读取授权及拒绝后不读取字节。

上述单元与接口测试不替代分角色浏览器回归。
