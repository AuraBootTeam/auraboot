/**
 * FileAttachment widget definition
 *
 * File upload field. Stores as JSON string [{name, url, size?, type?}].
 * Uploads via POST /api/files/upload.
 * NOTE BUG-7: files exceeding maxSize are silently skipped with no error feedback.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const fileattachmentWidget: WidgetDefinition = {
  component: 'fileattachment',
  name: 'File Attachment',
  icon: '📎',
  category: 'input',
  description: 'File upload and attachment list',
  schema: [
    {
      key: 'multiple',
      label: 'Multiple Files',
      type: 'boolean',
      group: 'FileAttachment',
      defaultValue: true,
    },
    {
      key: 'accept',
      label: 'Accepted File Types',
      type: 'text',
      group: 'FileAttachment',
      placeholder: '.pdf,.docx,image/*',
      description: 'Comma-separated MIME types or extensions. Empty = all files.',
    },
    {
      key: 'maxSize',
      label: 'Max File Size (MB)',
      type: 'number',
      group: 'FileAttachment',
      defaultValue: 10,
      description: 'Maximum size per file in MB. Files exceeding this are silently skipped (BUG-7).',
    },
  ],
};
