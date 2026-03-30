import { route } from '@react-router/dev/routes';

/**
 * Core routes — included in ALL editions (community, enterprise, website).
 * File paths are relative to the app/ directory.
 */
export function coreRoutes() {
  return [
    // Page Designer
    route('/page-designer', './routes/page-designer.tsx'),
    route('/page-designer/:id', './routes/page-designer.$id.tsx'),

    // BPMN Designer
    route('/bpmn-designer', './routes/bpmn-designer.tsx'),

    // Flow Designer
    route('/flow-designer', './routes/flow-designer.tsx'),

    // Automation
    route('/automations', './routes/automations.tsx'),
    route('/automation/:id', './routes/automation.$id.tsx'),

    // BPM (process-management, domain-configs, sla-configs are DSL pages under /dynamic/)
    route('/bpm/task-center', './routes/bpm/task-center.tsx'),
    route('/bpm/process-status', './routes/bpm/process-status.tsx'),
    route('/bpm/sla-monitor', './routes/bpm/sla-monitor.tsx'),
    route('/bpm/approval-inbox', './routes/bpm/approval-inbox.tsx'),

    // Organization management
    route('/organization/members/:memberPid', './routes/organization/member-detail.tsx'),
    route('/organization/teams', './routes/organization/teams.tsx'),
    route('/organization/teams/:teamPid', './routes/organization/team-detail.tsx'),

    // Model management
    route('/meta/models', './routes/meta/models/index.tsx'),
    route('/meta/models/new', './routes/meta/models/new.tsx'),
    route('/meta/models/:pid', './routes/meta/models/$pid.tsx'),
    route('/meta/models/:pid/edit', './routes/meta/models/$pid.edit.tsx'),

    // Field management
    route('/meta/fields', './routes/meta/fields/index.tsx'),
    route('/meta/fields/new', './routes/meta/fields/new.tsx'),
    route('/meta/fields/:pid', './routes/meta/fields/$pid.tsx'),
    route('/meta/fields/:pid/usage', './routes/meta/fields/$pid.usage.tsx'),
    route('/meta/fields/:pid/impact', './routes/meta/fields/$pid.impact.tsx'),

    // Dict management
    route('/meta/dict', './routes/meta/dict/index.tsx'),
    route('/meta/dict/new', './routes/meta/dict/new.tsx'),
    route('/meta/dict/:pid', './routes/meta/dict/$pid.tsx'),
    route('/meta/dict/:pid/edit', './routes/meta/dict/$pid.edit.tsx'),

    // Named Query management
    route('/meta/named-queries', './routes/meta/named-queries/index.tsx'),
    route('/meta/named-queries/new', './routes/meta/named-queries/new.tsx'),
    route('/meta/named-queries/:pid', './routes/meta/named-queries/$pid.tsx'),

    // Consistency Rules (GAP-081)
    route('/meta/consistency-rules', './routes/meta/consistency-rules/index.tsx'),

    // AI Natural Language Modeling
    route('/meta/ai-modeling', './routes/meta/ai-modeling/index.tsx'),

    // Query Builder
    route('/query-builder', './routes/query-builder.tsx'),

    // Document Editor
    route('/documents', './routes/documents/index.tsx'),

    // Personal profile
    route('/personal/profile', './routes/personal/profile.tsx'),
    route('/personal/security', './routes/personal/security.tsx'),
    route('/personal/social-links', './routes/personal/social-links.tsx'),
    route('/personal/deactivation', './routes/personal/deactivation.tsx'),

    // Settings
    route('/settings/plugins', './routes/settings/PluginManagement.tsx'),
    route('/settings/user-preferences', './routes/settings/user-preferences.tsx'),
    route('/settings/system-preferences', './routes/settings/system-preferences.tsx'),
    route('/settings/notification-preferences', './routes/settings/notification-preferences.tsx'),
    route('/settings/billing', './routes/settings/billing.tsx'),
    route('/settings/webhooks', './routes/settings/webhooks.tsx'),
    route('/settings/api-docs', './routes/settings/api-docs.tsx'),
    route('/settings/connectors', './routes/settings/connectors.tsx'),

    // Multi-Currency & Timezone
    route('/settings/exchange-rates', './routes/settings/exchange-rates.tsx'),
    route('/settings/timezone', './routes/settings/timezone.tsx'),

    // i18n Translation Coverage Dashboard
    route('/settings/i18n-coverage', './routes/settings/i18n-coverage.tsx'),

    // i18n Translation Workflow (Review & Approval)
    route('/settings/i18n-workflow', './routes/settings/i18n-workflow.tsx'),

    // Notifications
    route('/notifications', './routes/notifications/index.tsx'),

    // Notification rules
    route('/notification-rules', './routes/notification-rules/index.tsx'),

    // Scheduler
    route('/scheduler', './routes/scheduler/index.tsx'),

    // Audit logs
    route('/audit-logs', './routes/audit-logs/index.tsx'),

    // System plugins
    route('/system/plugins', './routes/system/plugins/index.tsx'),

    // Admin
    route('/admin/document-upload', './routes/admin/document-upload.tsx'),
    route('/admin/cloud-config', './routes/admin/cloud-config.tsx'),
    route('/admin/login-channels', './routes/admin/login-channels.tsx'),
    route('/admin/entitlements', './routes/admin/entitlements.tsx'),
    route('/admin/infrastructure', './routes/admin/infrastructure.tsx'),
    route('/admin/templates', './routes/admin/templates.tsx'),
    route('/admin/templates/:templateId/preview', './routes/admin/templates.$templateId.preview.tsx'),
    route('/admin/environments', './routes/admin/environments.tsx'),

    // AuraBot — Dashboard
    route('/aurabot/dashboard', './routes/mission-control/index.tsx'),

    // AuraBot — Trace Console
    route('/aurabot/traces', './routes/ai-trace/index.tsx'),
    route('/aurabot/traces/:traceId', './routes/ai-trace/$traceId.tsx'),

    // AuraBot — Run Log
    route('/aurabot/runs', './routes/aurabot/runs.tsx'),

    // AI Center — Settings Hub
    route('/ai/settings', './routes/ai/settings.tsx'),

    // AI Colleagues — Agent card grid, creation wizard, detail page, and full-page chat
    route('/ai/colleagues', './routes/ai/colleagues.tsx'),
    route('/ai/colleagues/new', './routes/ai/colleagues.new.tsx'),
    route('/ai/colleagues/:agentPid/chat', './routes/ai/colleagues.$agentPid.chat.tsx'),
    route('/ai/colleagues/:agentPid', './routes/ai/colleagues.$agentPid.tsx'),

    // AuraBot — LLM Providers & Prompt Templates
    route('/aurabot/providers', './routes/aurabot/providers.tsx'),
    route('/aurabot/prompts', './routes/aurabot/prompts.tsx'),

    // AuraBot — RAG Knowledge Base
    route('/aurabot/knowledge', './routes/aurabot/knowledge.tsx'),
    route('/aurabot/knowledge/:kbPid', './routes/aurabot/knowledge.$kbPid.tsx'),

    // Page routes — /p/:pageKey based (V2, underscores)
    route('/p/:pageKey', './routes/p.$pageKey.tsx'),
    route('/p/:pageKey/new', './routes/p.$pageKey.new.tsx'),
    route('/p/:pageKey/view/:recordId', './routes/p.$pageKey.view.tsx'),
    route('/p/:pageKey/:recordId/edit', './routes/p.$pageKey.edit.tsx'),

    // Legacy dynamic routes — redirect to /p/ prefix
    route('/dynamic/:tableName', './routes/dynamic.$tableName.tsx'),
    route('/dynamic/:tableName/new', './routes/dynamic.$tableName.new.tsx'),
    route('/dynamic/:tableName/view/:recordId', './routes/dynamic.$tableName.view.tsx'),
    route('/dynamic/:tableName/:recordId/edit', './routes/dynamic.$tableName.edit.tsx'),

    // Wildcard route — handles plugin-defined custom paths
    route('/*', './routes/$.tsx'),
  ];
}
