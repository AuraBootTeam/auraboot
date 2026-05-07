/**
 * CurrencyField
 *
 * Renders a numeric value as a localized currency string using
 * `Intl.NumberFormat`. Falls back to em-dash for null/undefined/NaN inputs.
 */

export interface CurrencyFieldProps {
  value: number | string | null | undefined;
  /** ISO 4217 currency code, defaults to CNY. */
  currencyCode?: string;
}

export function CurrencyField({ value, currencyCode = 'CNY' }: CurrencyFieldProps) {
  const num = typeof value === 'string' ? Number(value) : value;
  if (num === null || num === undefined || Number.isNaN(num)) {
    return <span data-field-type="currency">—</span>;
  }
  const formatted = new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: currencyCode,
  }).format(num as number);
  return <span data-field-type="currency">{formatted}</span>;
}
