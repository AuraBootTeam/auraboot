/**
 * MoneyInput widget definition
 *
 * Currency input with exchange rate, base currency display, and full FieldBase
 * integration. Supports decimal/money data types.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const moneyinputWidget: WidgetDefinition = {
  component: 'moneyinput',
  name: 'Money Input',
  icon: '💰',
  category: 'input',
  description: 'Currency amount input with exchange rate support',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'MoneyInput',
    },
    {
      key: 'precision',
      label: 'Decimal Precision',
      type: 'number',
      group: 'MoneyInput',
      defaultValue: 2,
    },
    {
      key: 'min',
      label: 'Min Value',
      type: 'number',
      group: 'MoneyInput',
    },
    {
      key: 'max',
      label: 'Max Value',
      type: 'number',
      group: 'MoneyInput',
    },
    {
      key: 'size',
      label: 'Size',
      type: 'select',
      group: 'MoneyInput',
      defaultValue: 'medium',
      options: [
        { label: 'Small', value: 'small' },
        { label: 'Medium', value: 'medium' },
        { label: 'Large', value: 'large' },
      ],
    },
    {
      key: 'variant',
      label: 'Variant',
      type: 'select',
      group: 'MoneyInput',
      defaultValue: 'default',
      options: [
        { label: 'Default', value: 'default' },
        { label: 'Outlined', value: 'outlined' },
        { label: 'Filled', value: 'filled' },
      ],
    },
    {
      key: 'currencyCode',
      label: 'Currency Code',
      type: 'text',
      group: 'Currency',
      placeholder: 'USD',
      description: 'ISO 4217 currency code',
    },
    {
      key: 'currencySymbol',
      label: 'Currency Symbol',
      type: 'text',
      group: 'Currency',
      placeholder: '$',
    },
    {
      key: 'baseCurrencySymbol',
      label: 'Base Currency Symbol',
      type: 'text',
      group: 'Currency',
      defaultValue: '¥',
    },
    {
      key: 'exchangeRate',
      label: 'Exchange Rate',
      type: 'number',
      group: 'Currency',
      description: 'Rate relative to base currency for conversion display',
    },
    {
      key: 'showBaseEquivalent',
      label: 'Show Base Equivalent',
      type: 'boolean',
      group: 'Currency',
      defaultValue: true,
      description: 'Show converted amount in base currency',
    },
  ],
};
