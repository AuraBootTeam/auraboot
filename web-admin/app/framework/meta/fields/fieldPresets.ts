/**
 * Field Template Presets
 *
 * Pre-defined groups of commonly-used fields that can be added in one click.
 * Each group represents a business domain concern (contact info, amounts, etc.)
 */

export interface FieldPreset {
  code: string;
  label: string; // i18n key
  type: 'string' | 'text' | 'enum' | 'date' | 'datetime' | 'decimal' | 'integer';
  required?: boolean;
}

export interface FieldPresetGroup {
  id: string;
  nameKey: string; // i18n key
  icon: string;
  description: string;
  fields: FieldPreset[];
}

export const fieldPresetGroups: FieldPresetGroup[] = [
  {
    id: 'basic',
    nameKey: 'onboarding.addFields.group.basic',
    icon: 'FileText',
    description: 'Name, code, description',
    fields: [
      { code: 'name', label: 'field.name', type: 'string', required: true },
      { code: 'code', label: 'field.code', type: 'string' },
      { code: 'description', label: 'field.description', type: 'text' },
    ],
  },
  {
    id: 'status',
    nameKey: 'onboarding.addFields.group.status',
    icon: 'ToggleLeft',
    description: 'Status, priority',
    fields: [
      { code: 'status', label: 'field.status', type: 'enum', required: true },
      { code: 'priority', label: 'field.priority', type: 'enum' },
    ],
  },
  {
    id: 'time',
    nameKey: 'onboarding.addFields.group.time',
    icon: 'Clock',
    description: 'Start date, end date, created at',
    fields: [
      { code: 'start_date', label: 'field.startDate', type: 'date' },
      { code: 'end_date', label: 'field.endDate', type: 'date' },
      { code: 'created_at', label: 'field.createdAt', type: 'datetime' },
    ],
  },
  {
    id: 'amount',
    nameKey: 'onboarding.addFields.group.amount',
    icon: 'DollarSign',
    description: 'Amount, currency, tax rate',
    fields: [
      { code: 'amount', label: 'field.amount', type: 'decimal', required: true },
      { code: 'currency', label: 'field.currency', type: 'enum' },
      { code: 'tax_rate', label: 'field.taxRate', type: 'decimal' },
    ],
  },
  {
    id: 'contact',
    nameKey: 'onboarding.addFields.group.contact',
    icon: 'User',
    description: 'Contact name, phone, email',
    fields: [
      { code: 'contact_name', label: 'field.contactName', type: 'string' },
      { code: 'phone', label: 'field.phone', type: 'string' },
      { code: 'email', label: 'field.email', type: 'string' },
    ],
  },
  {
    id: 'address',
    nameKey: 'onboarding.addFields.group.address',
    icon: 'MapPin',
    description: 'Address, city, country',
    fields: [
      { code: 'address', label: 'field.address', type: 'text' },
      { code: 'city', label: 'field.city', type: 'string' },
      { code: 'country', label: 'field.country', type: 'string' },
    ],
  },
];

/**
 * Get a preset group by ID
 */
export function getFieldPresetGroup(id: string): FieldPresetGroup | undefined {
  return fieldPresetGroups.find((g) => g.id === id);
}
