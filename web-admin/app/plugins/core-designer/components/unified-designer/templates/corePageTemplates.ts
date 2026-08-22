import type { PageTemplate } from './pageTemplateRegistry';

/**
 * Small, server-capability-backed starting points. They intentionally contain no model field
 * guesses: authors bind real published fields from the field palette after applying a template.
 */
export const CORE_PAGE_TEMPLATES: PageTemplate[] = [
  {
    id: 'core_list_workspace',
    version: '1',
    label: '列表工作台',
    category: 'core',
    kinds: ['list'],
    build: () => [{
      id: 'list_root',
      blockType: 'list',
      blocks: [
        { id: 'filters', blockType: 'filter-bar', blocks: [] },
        { id: 'table', blockType: 'table', blocks: [] },
      ],
    }],
  },
  {
    id: 'core_form_sections',
    version: '1',
    label: '分组表单',
    category: 'core',
    kinds: ['form'],
    build: () => [{
      id: 'form_root',
      blockType: 'form',
      blocks: [
        { id: 'primary', blockType: 'form-section', title: '基本信息', blocks: [] },
        { id: 'more', blockType: 'form-section', title: '补充信息', blocks: [] },
      ],
    }],
  },
  {
    id: 'core_detail_summary',
    version: '1',
    label: '详情摘要',
    category: 'core',
    kinds: ['detail'],
    build: () => [{
      id: 'detail_root',
      blockType: 'detail',
      blocks: [
        { id: 'summary', blockType: 'detail-section', title: '摘要', blocks: [] },
        { id: 'description', blockType: 'description', props: { content: '在此补充说明' } },
      ],
    }],
  },
  {
    id: 'core_dashboard_overview',
    version: '1',
    label: '经营概览',
    category: 'core',
    kinds: ['dashboard'],
    build: () => [{
      id: 'dashboard_root',
      blockType: 'dashboard',
      blocks: [
        { id: 'metric', blockType: 'stat-card', title: '核心指标' },
        { id: 'note', blockType: 'description', props: { content: '在此补充指标说明' } },
      ],
    }],
  },
];
