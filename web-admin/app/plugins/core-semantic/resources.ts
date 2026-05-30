import type { PluginResource } from '../_shared/types.js';

export const RESOURCES: PluginResource[] = [
  {
    key: 'semantic.lineage',
    path: '/semantic/lineage',
    title: { en: 'Data Lineage', zh: '数据血缘' },
    icon: 'git-branch',
    menu: { order: 60, group: 'semantic' },
    file: './routes/semantic-lineage/index.tsx',
  },
];
