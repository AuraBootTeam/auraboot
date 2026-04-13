import type { BlockDefinition } from '../../types';

export const tabsBlock: BlockDefinition = {
  type: 'tabs',
  name: 'Tabs',
  icon: '⬚',
  description: 'Multi-tab container',
  category: 'layout',
  defaultColSpan: 12,
  schema: [
    // ── Tab Configuration ──────────────────────────────────────
    {
      key: 'defaultActiveTab',
      label: 'Default Active Tab',
      type: 'text',
      group: 'Tabs',
      placeholder: 'e.g. overview',
      description: 'Key of the tab to show by default',
    },
    {
      key: 'tabPosition',
      label: 'Tab Position',
      type: 'select',
      group: 'Tabs',
      defaultValue: 'top',
      options: [
        { label: 'Top', value: 'top' },
        { label: 'Left', value: 'left' },
        { label: 'Bottom', value: 'bottom' },
        { label: 'Right', value: 'right' },
      ],
    },
    {
      key: 'tabs',
      label: 'Tabs (JSON)',
      type: 'json',
      group: 'Tabs',
      description: 'Array of {key, label, blocks?, visibleWhen?} objects. Each tab can contain nested blocks.',
    },

    // ── Conditions ──────────────────────────────────────────────
    {
      key: 'visibleWhen',
      label: 'Visible when',
      type: 'expression',
      group: 'Conditions',
      description: 'Condition expression to control block visibility',
    },
    {
      key: 'className',
      label: 'CSS Class',
      type: 'text',
      group: 'Conditions',
      placeholder: 'e.g. compact-tabs',
    },
  ],
};
