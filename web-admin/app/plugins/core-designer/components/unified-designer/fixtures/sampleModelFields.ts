import type { ModelFieldsByModel } from '../types';

export const sampleModelFieldsByModel: ModelFieldsByModel = {
  customer: [
    {
      modelCode: 'customer',
      code: 'email',
      label: 'Email',
      type: 'email',
      component: 'input',
      required: true,
    },
    {
      modelCode: 'customer',
      code: 'status',
      label: 'Status',
      type: 'select',
      component: 'select',
    },
    {
      modelCode: 'customer',
      code: 'owner',
      label: 'Owner',
      type: 'relation',
      component: 'select',
    },
    {
      modelCode: 'customer',
      code: 'notes',
      label: 'Notes',
      type: 'longText',
      component: 'textarea',
    },
  ],
};
