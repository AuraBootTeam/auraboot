import type { BlockDefinition } from '../../types';

export const statCardBlock: BlockDefinition = {
  type: 'stat-card',
  name: 'Stat Card',
  icon: '🔢',
  description: 'Key metric numbers',
  category: 'data',
  defaultColSpan: 4,
  schema: [
    // ── Data Source ──────────────────────────────────────────────
    {
      key: 'dataSource.queryCode',
      label: 'Named Query code',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. dashboard_stats',
      description: 'The query must return a single row with named columns',
    },

    // ── Conditions ──────────────────────────────────────────────
    {
      key: 'visibleWhen',
      label: 'Visible when',
      type: 'expression',
      group: 'Conditions',
      description: 'Condition expression to control block visibility',
    },
  ],
};
