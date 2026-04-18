/**
 * RichText widget definition
 *
 * TipTap-based rich text editor with bold/italic/headings/lists/blockquote/
 * codeBlock/link toolbar. Stores HTML as text.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const richtextWidget: WidgetDefinition = {
  component: 'richtext',
  name: 'Rich Text',
  icon: '📝',
  category: 'input',
  description: 'TipTap rich text editor with full formatting toolbar',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'RichText',
    },
  ],
};
