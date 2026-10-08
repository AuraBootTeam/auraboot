---
type: system-reference
status: active
created: 2026-10-09
---

# 外部事件自动化触发类型

Open Platform 事件入口将外部事件交给 `AutomationTriggerServiceImpl.onExternalEvent`；消费规则使用 `external_event` 触发类型。`AutomationServiceImpl` 的服务校验和 `ab_automation.chk_automation_trigger_type` 数据库约束必须同时允许它。

平台数据库允许九种触发类型：`on_record_create`、`on_record_update`、`on_field_change`、`on_state_change`、`scheduled`、`webhook`、`on_workflow_event`、`on_inactivity`、`external_event`。未知值仍由 CHECK 约束拒绝。

迁移真源为 `platform/src/main/resources/db/migration/core/V20261006190000__add_external_event_trigger_type.sql`；迁移版本和数量同时登记在 `scripts/db/migration-version-floor.txt`。schema snapshot 是生成物，禁止从历史发布目录复制或手改；发布时通过 `scripts/db/generate-schema-snapshot.sh` 从迁移结果生成。

定向 PostgreSQL 验证入口为 `scripts/db/external-event-trigger.integration.sql`，在隔离库完成 Flyway migrate 后用 `psql -v ON_ERROR_STOP=1 -f` 执行。该入口实际写入九种类型，确认未知类型被准确约束拒绝，并回滚全部夹具；它不代表 HTTP 事件入口或下游自动化执行通过。
