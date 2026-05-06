import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  { key: 'admin.document-upload', path: '/admin/document-upload', title: { en: 'Document Upload', zh: '文档上传' }, icon: 'upload', menu: { order: 10, group: 'admin' }, permission: 'admin.manage', file: './plugins/core-admin/pages/admin/document-upload.tsx' },
  { key: 'admin.cloud-config', path: '/admin/cloud-config', title: { en: 'Cloud Config', zh: '云配置' }, icon: 'cloud', menu: { order: 20, group: 'admin' }, permission: 'admin.manage', file: './plugins/core-admin/pages/admin/cloud-config.tsx' },
  { key: 'admin.login-channels', path: '/admin/login-channels', title: { en: 'Login Channels', zh: '登录渠道' }, icon: 'log-in', menu: { order: 30, group: 'admin' }, permission: 'admin.manage', file: './plugins/core-admin/pages/admin/login-channels.tsx' },
  { key: 'admin.entitlements', path: '/admin/entitlements', title: { en: 'Entitlements', zh: '授权管理' }, icon: 'key', menu: { order: 40, group: 'admin' }, permission: 'admin.manage', file: './plugins/core-admin/pages/admin/entitlements.tsx' },
  { key: 'admin.infrastructure', path: '/admin/infrastructure', title: { en: 'Infrastructure', zh: '基础设施' }, icon: 'server', menu: { order: 50, group: 'admin' }, permission: 'admin.manage', file: './plugins/core-admin/pages/admin/infrastructure.tsx' },
  { key: 'admin.templates', path: '/admin/templates', title: { en: 'Templates', zh: '模板' }, icon: 'file-template', menu: { order: 60, group: 'admin' }, permission: 'admin.read', file: './plugins/core-admin/pages/admin/templates.tsx' },
  { key: 'admin.template-preview', path: '/admin/templates/:templateId/preview', title: { en: 'Template Preview', zh: '模板预览' }, menu: false, parentKey: 'admin.templates', permission: 'admin.read', file: './plugins/core-admin/pages/admin/templates.$templateId.preview.tsx' },
  { key: 'admin.environments', path: '/admin/environments', title: { en: 'Environments', zh: '环境' }, icon: 'layers', menu: { order: 70, group: 'admin' }, permission: 'admin.manage', file: './plugins/core-admin/pages/admin/environments.tsx' },
  { key: 'admin.diff', path: '/admin/diff', title: { en: 'Diff Viewer', zh: '差异对比' }, menu: false, parentKey: 'admin.environments', permission: 'admin.manage', file: './plugins/core-admin/pages/admin/diff.tsx' },
  { key: 'admin.permissions', path: '/enterprise/permissions', title: { en: 'Permissions', zh: '权限管理' }, icon: 'shield-check', menu: { order: 80, group: 'admin' }, permission: 'admin.manage', file: './routes/enterprise/PermissionManagement.tsx' },
]
