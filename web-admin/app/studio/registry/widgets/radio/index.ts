/**
 * Radio widget definition
 *
 * Radio-button group supporting static options, dictionary codes, or API.
 * Shares the options-source schema with the Select widget.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from '../../types';

export const radioWidget: WidgetDefinition = {
  component: 'radio',
  name: 'Radio',
  icon: '◉',
  category: 'input',
  description: 'Radio group',
  schema: [
    {
      key: 'optionsSource',
      label: 'Options Source',
      type: 'select',
      group: 'Options',
      defaultValue: 'static',
      options: [
        { label: 'Static List', value: 'static' },
        { label: 'Dictionary', value: 'dict' },
        { label: 'API', value: 'api' },
      ],
    },
    {
      key: 'options',
      label: 'Options (JSON)',
      type: 'json',
      group: 'Options',
      description: 'Array of {label, value} objects. e.g. [{"label":"Male","value":"male"},{"label":"Female","value":"female"}]',
    },
    {
      key: 'dictCode',
      label: 'Dictionary Code',
      type: 'text',
      group: 'Options',
      description: 'System dictionary code for auto-loading options',
    },
    {
      key: 'optionsApi',
      label: 'Options API',
      type: 'text',
      group: 'Options',
      placeholder: '/api/options/categories',
      description: 'API endpoint returning [{label, value}] array',
    },
  ],
};
