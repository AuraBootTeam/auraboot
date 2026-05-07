import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'platform.plugins',
    path: '/plugins',
    title: { en: 'Plugins', zh: '插件管理' },
    icon: 'package',
    menu: { order: 10, group: 'platform' },
    file: './plugins/core-platform/pages/plugins/index.tsx',
  },
  {
    key: 'platform.plugin-solution-detail',
    path: '/plugins/solutions/:code',
    title: { en: 'Solution Detail', zh: '解决方案详情' },
    icon: 'package',
    file: './plugins/core-platform/pages/plugins/solutions/$code.tsx',
  },
  {
    key: 'platform.plugin-detail',
    path: '/plugins/:pluginId',
    title: { en: 'Plugin Detail', zh: '插件详情' },
    icon: 'package',
    file: './plugins/core-platform/pages/plugins/$pluginId.tsx',
  },
  {
    key: 'platform.kernel-plugins',
    path: '/system/kernel-plugins',
    title: { en: 'Kernel Plugins', zh: '内核插件' },
    icon: 'cpu',
    menu: { order: 20, group: 'platform' },
    file: './plugins/core-platform/pages/KernelPluginsPage.tsx',
  },
  // C.2 cross-tenant sub-agent ACL admin surface — list / grant / revoke /
  // audit. Backend gates platform_admin role; the menu entry stays visible
  // for tenant_admin too (the page renders the 403 banner from the API
  // response when the user lacks platform_admin).
  {
    key: 'platform.cross-tenant-grants',
    path: '/admin/cross-tenant-grants',
    title: { en: 'Cross-Tenant Grants', zh: '跨租户授权' },
    icon: 'shield',
    menu: { order: 30, group: 'platform' },
    file: './plugins/core-platform/pages/CrossTenantGrantsPage.tsx',
  },
]
