import type { BlockDefinition } from '../../types';

export const richTextBlock: BlockDefinition = {
  type: 'rich-text',
  name: 'Rich Text',
  icon: 'T',
  description: 'Markdown content',
  category: 'display',
  defaultColSpan: 6,
  schema: [
    {
      key: 'content',
      label: 'Content',
      type: 'textarea',
      group: 'Content',
      placeholder: 'Enter HTML or markdown content...',
      description: 'Supports HTML. Use LocalizedText object for i18n.',
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
