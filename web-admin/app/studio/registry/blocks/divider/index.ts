import type { BlockDefinition } from '../../types';

export const dividerBlock: BlockDefinition = {
  type: 'divider',
  name: 'Divider',
  icon: '―',
  description: 'Visual separator',
  category: 'layout',
  defaultColSpan: 12,
  schema: [
    {
      key: 'visibleWhen',
      label: 'Visible when',
      type: 'expression',
      group: 'Conditions',
      description: 'Condition expression to control block visibility',
    },
  ],
};
