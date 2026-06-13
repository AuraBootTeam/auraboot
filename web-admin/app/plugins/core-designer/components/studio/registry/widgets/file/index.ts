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

export const uploadWidget: WidgetDefinition = {
  component: 'upload',
  name: 'Upload',
  icon: '↑',
  category: 'input',
  description: 'SmartUpload runtime field',
  schema: [
    {
      key: 'accept',
      label: 'Accepted File Types',
      type: 'text',
      group: 'Upload',
      placeholder: '.pdf,.doc,.docx,.jpg,.png',
      description: 'Comma-separated MIME types or file extensions. Empty = all files.',
    },
    {
      key: 'multiple',
      label: 'Allow Multiple Files',
      type: 'boolean',
      group: 'Upload',
      defaultValue: false,
    },
    {
      key: 'maxSize',
      label: 'Max File Size (MB)',
      type: 'number',
      group: 'Upload',
      defaultValue: 10,
    },
    {
      key: 'maxCount',
      label: 'Max File Count',
      type: 'number',
      group: 'Upload',
      defaultValue: 1,
    },
    {
      key: 'listType',
      label: 'List Type',
      type: 'select',
      group: 'Upload',
      defaultValue: 'text',
      options: [
        { label: 'Text', value: 'text' },
        { label: 'Picture', value: 'picture' },
        { label: 'Picture Card', value: 'picture-card' },
      ],
    },
    {
      key: 'buttonText',
      label: 'Button Text',
      type: 'text',
      group: 'Upload',
      defaultValue: 'Click to upload',
    },
    {
      key: 'hint',
      label: 'Hint',
      type: 'text',
      group: 'Upload',
    },
  ],
};
