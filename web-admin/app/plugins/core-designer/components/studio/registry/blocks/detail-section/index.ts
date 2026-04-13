import type { BlockDefinition } from '../../types';

export const detailSectionBlock: BlockDefinition = {
  type: 'detail-section',
  name: 'Detail Section',
  icon: '▣',
  description: 'Read-only field display',
  category: 'display',
  defaultColSpan: 6,
  schema: [
    // ── Data Source ──
    {
      key: 'dataSource.modelCode',
      label: 'Model',
      type: 'model-select',
      group: 'Data Source',
    },

    // ── Layout ──
    {
      key: 'colCount',
      label: 'Columns',
      type: 'select',
      group: 'Layout',
      defaultValue: '3',
      options: [
        { label: '2 Columns', value: '2' },
        { label: '3 Columns', value: '3' },
        { label: '4 Columns', value: '4' },
      ],
    },

    // ── Conditions ──
    {
      key: 'visibleWhen',
      label: 'Visible when',
      type: 'expression',
      group: 'Conditions',
      description: 'Condition expression to control block visibility',
    },
  ],
};
