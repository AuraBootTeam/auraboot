/**
 * File Upload widget definition
 *
 * File attachment field with accepted-type filter, max size, and max count constraints.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from '../../types';

export const fileWidget: WidgetDefinition = {
  component: 'file',
  name: 'File Upload',
  icon: '↑',
  category: 'input',
  description: 'File upload',
  schema: [
    {
      key: 'accept',
      label: 'Accepted File Types',
      type: 'text',
      group: 'File',
      placeholder: '.pdf,.doc,.docx,.jpg,.png',
      description: 'Comma-separated file extensions',
    },
    {
      key: 'maxFileSize',
      label: 'Max File Size (MB)',
      type: 'number',
      group: 'File',
    },
    {
      key: 'maxFileCount',
      label: 'Max File Count',
      type: 'number',
      group: 'File',
    },
  ],
};
