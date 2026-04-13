import type { BlockDefinition } from '../../types';

export const filtersBlock: BlockDefinition = {
  type: 'filters',
  name: 'Filters',
  icon: '⫧',
  description: 'Standalone filter panel',
  category: 'form',
  defaultColSpan: 12,
  schema: [
    // ── Data Source ──
    {
      key: 'dataSource.modelCode',
      label: 'Model',
      type: 'model-select',
      group: 'Data Source',
      description: 'Model to derive filter fields from',
    },

    // ── Layout ──
    {
      key: 'colCount',
      label: 'Columns',
      type: 'select',
      group: 'Layout',
      defaultValue: '4',
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
