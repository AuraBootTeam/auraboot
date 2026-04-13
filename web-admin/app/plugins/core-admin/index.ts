import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources([
      { key: 'admin.document-upload', path: '/admin/document-upload', title: { en: 'Document Upload', zh: '文档上传' }, icon: 'upload', menu: { order: 10, group: 'admin' }, permission: 'admin.manage', source: 'plugin' },
      { key: 'admin.cloud-config', path: '/admin/cloud-config', title: { en: 'Cloud Config', zh: '云配置' }, icon: 'cloud', menu: { order: 20, group: 'admin' }, permission: 'admin.manage', source: 'plugin' },
      { key: 'admin.login-channels', path: '/admin/login-channels', title: { en: 'Login Channels', zh: '登录渠道' }, icon: 'log-in', menu: { order: 30, group: 'admin' }, permission: 'admin.manage', source: 'plugin' },
      { key: 'admin.entitlements', path: '/admin/entitlements', title: { en: 'Entitlements', zh: '授权管理' }, icon: 'key', menu: { order: 40, group: 'admin' }, permission: 'admin.manage', source: 'plugin' },
      { key: 'admin.infrastructure', path: '/admin/infrastructure', title: { en: 'Infrastructure', zh: '基础设施' }, icon: 'server', menu: { order: 50, group: 'admin' }, permission: 'admin.manage', source: 'plugin' },
      { key: 'admin.templates', path: '/admin/templates', title: { en: 'Templates', zh: '模板' }, icon: 'file-template', menu: { order: 60, group: 'admin' }, permission: 'admin.read', source: 'plugin' },
      { key: 'admin.template-preview', path: '/admin/templates/:templateId/preview', title: { en: 'Template Preview', zh: '模板预览' }, menu: false, parentKey: 'admin.templates', permission: 'admin.read', source: 'plugin' },
      { key: 'admin.environments', path: '/admin/environments', title: { en: 'Environments', zh: '环境' }, icon: 'layers', menu: { order: 70, group: 'admin' }, permission: 'admin.manage', source: 'plugin' },
    ])
  },
})
