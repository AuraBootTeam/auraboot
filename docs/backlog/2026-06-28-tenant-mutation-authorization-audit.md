---
title: 平台审计 — 租户级 mutation 端点缺角色门禁(越权)
type: backlog
status: open
created: 2026-06-28
---

# 平台安全审计:租户级 mutation 端点缺角色门禁

## 背景

2026-06-28 Quote/BOM 交付 SOT 三角色真机验收中,用非 admin(工程)token 实测,发现一类系统性越权:
**租户级写操作的 Controller 端点缺 `@RequirePermission`,只有 service 层的租户隔离(同租户即放行),
没有角色门禁** → 同租户的普通成员能执行本应是管理员的操作。

这一类已确认并修复 2 个(都在 Quote/BOM 范围内):
- **MENU-P0-08**:`TenantPreferenceController.setPreference` 无门禁 → 非 admin 改全租户偏好(已修 #1105)。
- **成员管理**:`TenantMemberController` 全部 6 个 mutation 无门禁 → 非 admin approve/disable/suspend/delete/import 成员,suspend 还撤销会话(已修 #1106,加 `member_management`)。

本 backlog 记录**同类但不在 Quote/BOM 范围**的 3 个待审,以及审计方法,交平台线统一处理。

## 待处理项(均:租户级 mutation,无 `@RequirePermission`,eng token 实测返 400/200 而非 403)

| 端点 | 影响 | 严重度 | 建议门禁 |
|---|---|---|---|
| `RecordShareController` POST `/api/record-share` / DELETE `/{shareId}` | `RecordShareServiceImpl.shareRecord(tenantId,...)` 只按 tenantId 插 share 记录,**不校验调用者是否拥有/有权分享该记录** → 非 admin 能把任意记录授权给任意 subject = **访问控制绕过 / 数据越权暴露** | **高** | 校验调用者对该记录有 share/manage 权限(owner 或显式权限),而非仅 tenant 隔离 |
| `NotificationRuleController` POST / PUT `/{id}` / DELETE / PUT `/{id}/toggle` | 租户级通知规则(`list` 注释 "for the current tenant"),无门禁 → 非 admin 篡改全租户通知配置(关告警 / 改触发) | 中 | `@RequirePermission`(通知/自动化管理权限) |
| `AnnouncementController` POST / PUT `/{id}` / DELETE | 租户级公告,无门禁 → 非 admin 发 / 改 / 删全租户公告(冒充官方 / 删真公告) | 中 | `@RequirePermission`(公告/系统管理权限) |

## 审计方法(可复用)

1. 扫描:`grep` 所有 `*Controller.java` 中含 `@(Post|Put|Delete)Mapping` 但**全文件零 `@RequirePermission`** 且非 `/api/admin/**`(admin-guard 前缀)、非公开(auth/login/public/webhook/health)的 controller。
2. 三角色实测(本仓的判别信号):用非 admin token 发最小 payload —
   - **403 / 401**：有门禁,安全。
   - **`/api/admin/**` 返 200 + body `code:409 "admin role required"`**:admin-guard 拦住,安全。
   - **400 / 422 / 200(业务码 0)**:**绕过了权限检查、进了业务逻辑 = 嫌疑**,需读 service 层确认有无归属/角色校验。
3. service 层确认:tenant 隔离 ≠ 角色门禁。只比 `member.getTenantId() == currentTenantId` 不算授权。

## 完整候选清单

2026-06-28 扫描全平台 controller,共 **62 个**候选(有 mutation 但零 `@RequirePermission`)。多数是 user 自服务(`/api/user/sessions`、`/api/notifications`、IM、email、user-preferences、test、bootstrap 等,本就 user-scoped,非 bug)。需逐个按上述方法 triage 区分「自服务(安全)」vs「租户级管理(越权)」。本文件先列已确认 3 个高/中危;其余候选交平台线按方法续审。

## 范围说明

这 3 个是**平台通用功能,不涉 BOM/报价流程**,不在 `docs/system-reference/.../quote-bom-validation-sot.md` 验收矩阵内,因此**不阻塞 Quote/BOM 客户交付**。Quote/BOM 范围内的同类越权(偏好 #1105、成员 #1106)已修。`ab_record_share` 是数据权限「按分配共享」的未来扩展点,届时 record-share 授权校验需一并补齐。
