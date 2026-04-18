import React, { useEffect, useMemo, useState } from 'react';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { FieldBase } from '~/ui/ui/field-base';
import { REGIONS, type RegionNode } from './china-regions';

// ─── Types ────────────────────────────────────────────────────────────────────

export type { RegionNode };

export interface AddressValue {
  province: string;
  city: string;
  district: string;
  detail: string;
}

export interface AddressFieldProps {
  name: string;
  label?: string;
  placeholder?: string;
  value?: string | Partial<AddressValue>;
  defaultValue?: string | Partial<AddressValue>;
  onChange?: (value: AddressValue) => void;
  disabled?: boolean;
  required?: boolean;
  readOnly?: boolean;
  showDetailAddress?: boolean;
  detailMaxLength?: number;
  detailPlaceholder?: string;
  requireDistrict?: boolean;
  loadRegions?: () => Promise<RegionNode[]>;
  className?: string;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

const EMPTY_VALUE: AddressValue = { province: '', city: '', district: '', detail: '' };

function parseValue(raw: string | Partial<AddressValue> | undefined): AddressValue {
  if (!raw) return { ...EMPTY_VALUE };
  if (typeof raw === 'string') {
    if (!raw.trim()) return { ...EMPTY_VALUE };
    try {
      const parsed = JSON.parse(raw) as Partial<AddressValue>;
      return {
        province: parsed.province ?? '',
        city: parsed.city ?? '',
        district: parsed.district ?? '',
        detail: parsed.detail ?? '',
      };
    } catch {
      return { ...EMPTY_VALUE };
    }
  }
  return {
    province: raw.province ?? '',
    city: raw.city ?? '',
    district: raw.district ?? '',
    detail: raw.detail ?? '',
  };
}

// ─── Component ────────────────────────────────────────────────────────────────

export const AddressField: React.FC<AddressFieldProps> = ({
  name,
  label,
  placeholder,
  value,
  defaultValue,
  onChange,
  disabled = false,
  required = false,
  readOnly = false,
  showDetailAddress = true,
  detailMaxLength = 200,
  detailPlaceholder = '请输入详细地址',
  requireDistrict = false,
  loadRegions,
  className = '',
}) => {
  const { labelText, required: requiredValue, disabled: disabledValue } = useSmartFieldContract({
    label,
    placeholder,
    required,
    disabled,
  });
  const meta = useSmartFieldMeta({ externalError: undefined });

  // Async region data: start with built-in, allow override via loadRegions
  const [regions, setRegions] = useState<RegionNode[]>(REGIONS);

  useEffect(() => {
    if (!loadRegions) return;
    loadRegions()
      .then(setRegions)
      .catch(() => {
        // loadRegions failed — keep built-in dataset
      });
  }, [loadRegions]);

  // Controlled / uncontrolled address state
  const [localState, setLocalState] = useState<AddressValue>(() =>
    parseValue(value ?? defaultValue)
  );

  const controlled = value !== undefined;
  const state: AddressValue = controlled ? parseValue(value) : localState;

  // Derived city and district options
  const provinceNode = useMemo(
    () => regions.find((p) => p.name === state.province),
    [regions, state.province]
  );

  const cityNodes: RegionNode[] = provinceNode?.children ?? [];

  const cityNode = useMemo(
    () => cityNodes.find((c) => c.name === state.city),
    [cityNodes, state.city]
  );

  const districtNodes: RegionNode[] = cityNode?.children ?? [];

  // ── Event handlers ──────────────────────────────────────────────────────

  const isInteractive = !disabledValue && !readOnly;

  function updateState(next: AddressValue) {
    if (!controlled) setLocalState(next);
    onChange?.(next);
    meta.markTouched();
  }

  function handleProvinceChange(e: React.ChangeEvent<HTMLSelectElement>) {
    if (!isInteractive) return;
    updateState({ province: e.target.value, city: '', district: '', detail: state.detail });
  }

  function handleCityChange(e: React.ChangeEvent<HTMLSelectElement>) {
    if (!isInteractive) return;
    updateState({ ...state, city: e.target.value, district: '' });
  }

  function handleDistrictChange(e: React.ChangeEvent<HTMLSelectElement>) {
    if (!isInteractive) return;
    updateState({ ...state, district: e.target.value });
  }

  function handleDetailChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    if (!isInteractive) return;
    updateState({ ...state, detail: e.target.value });
  }

  // ── Styles ───────────────────────────────────────────────────────────────

  const selectBase =
    'w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm ' +
    'focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none ' +
    'disabled:cursor-not-allowed disabled:bg-gray-50 disabled:text-gray-500';

  const textareaBase =
    'w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm resize-none ' +
    'focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none ' +
    'disabled:cursor-not-allowed disabled:bg-gray-50 disabled:text-gray-500';

  // Hidden form value - only serialize non-empty state
  const hasValue =
    state.province || state.city || state.district || state.detail;
  const hiddenValue = hasValue ? JSON.stringify(state) : '';

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      error={meta.showError ? String(meta.meta.error ?? '') : undefined}
      className={`space-y-3 ${className}`}
    >
      {/* Province / City / District row */}
      <div className="grid grid-cols-3 gap-2">
        {/* Province */}
        <select
          aria-label="province"
          value={state.province}
          onChange={handleProvinceChange}
          disabled={!isInteractive}
          className={selectBase}
        >
          <option value="">请选择省份</option>
          {regions.map((p) => (
            <option key={p.code} value={p.name}>
              {p.name}
            </option>
          ))}
        </select>

        {/* City */}
        <select
          aria-label="city"
          value={state.city}
          onChange={handleCityChange}
          disabled={!isInteractive || !state.province}
          className={selectBase}
        >
          <option value="">请选择城市</option>
          {cityNodes.map((c) => (
            <option key={c.code} value={c.name}>
              {c.name}
            </option>
          ))}
        </select>

        {/* District */}
        <select
          aria-label="district"
          value={state.district}
          onChange={handleDistrictChange}
          disabled={!isInteractive || !state.city}
          className={selectBase}
        >
          <option value="">{requireDistrict ? '请选择区县 *' : '请选择区县'}</option>
          {districtNodes.map((d) => (
            <option key={d.code} value={d.name}>
              {d.name}
            </option>
          ))}
        </select>
      </div>

      {/* Detail address textarea */}
      {showDetailAddress && (
        <textarea
          aria-label="detail-address"
          rows={2}
          value={state.detail}
          onChange={handleDetailChange}
          disabled={!isInteractive}
          placeholder={detailPlaceholder}
          maxLength={detailMaxLength}
          className={textareaBase}
        />
      )}

      {/* Hidden input for form submission */}
      <input type="hidden" name={name} value={hiddenValue} />
    </FieldBase>
  );
};

export default AddressField;
