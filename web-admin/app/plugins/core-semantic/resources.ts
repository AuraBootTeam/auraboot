import type { PluginResource } from '../_shared/types.js';

export const RESOURCES: PluginResource[] = [
  {
    key: 'semantic.models',
    path: '/semantic/models',
    title: { en: 'Semantic Models', zh: '语义模型' },
    icon: 'database',
    menu: { order: 58, group: 'semantic' },
    file: './routes/semantic-models/index.tsx',
  },
  {
    key: 'semantic.lineage',
    path: '/semantic/lineage',
    title: { en: 'Data Lineage', zh: '数据血缘' },
    icon: 'git-branch',
    menu: { order: 60, group: 'semantic' },
    file: './routes/semantic-lineage/index.tsx',
  },
];
