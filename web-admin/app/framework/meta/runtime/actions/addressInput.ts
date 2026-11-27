export interface AddressInputOptions {
  required?: boolean;
  requireDistrict?: boolean;
  showDetailAddress?: boolean;
  detailMaxLength?: number;
}

/** Command prompts submit structured address values, never raw JSON or an ERP address string. */
export function validAddressInput(value: unknown, options: AddressInputOptions = {}): boolean {
  if (value == null || value === '') return !options.required;
  if (typeof value !== 'object' || Array.isArray(value)) return false;
  const keys = ['province', 'city', 'district', 'detail'] as const;
  const address = value as Record<string, unknown>;
  if (
    Object.keys(address).length !== keys.length ||
    keys.some((key) => typeof address[key] !== 'string')
  )
    return false;
  if (keys.every((key) => !(address[key] as string).trim())) return !options.required;
  const max = options.detailMaxLength ?? 200;
  if (!Number.isInteger(max) || max < 1) return false;
  if (!['province', 'city'].every((key) => (address[key] as string).trim().length > 0))
    return false;
  if ((options.requireDistrict ?? true) && !(address.district as string).trim()) return false;
  if ((options.showDetailAddress ?? true) && !(address.detail as string).trim()) return false;
  return keys.every((key) => (address[key] as string).length <= (key === 'detail' ? max : 100));
}
