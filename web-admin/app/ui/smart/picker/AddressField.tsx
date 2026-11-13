import React, { useEffect, useId, useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import TEXT from './AddressField.i18n.json';
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
  allowManualEntry?: boolean;
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
  detailPlaceholder,
  requireDistrict = false,
  allowManualEntry = false,
  loadRegions,
  className = '',
}) => {
  const { locale, t } = useI18n();
  const controlId = useId();
  const text = (key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale, t);
  const {
    labelText,
    required: requiredValue,
    disabled: disabledValue,
  } = useSmartFieldContract({
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
    parseValue(value ?? defaultValue),
  );

  const [manualEntry, setManualEntry] = useState(() => {
    if (!allowManualEntry) return false;
    const initial = parseValue(value ?? defaultValue);
    const province = REGIONS.find((node) => node.name === initial.province);
    const city = province?.children?.find((node) => node.name === initial.city);
    return Boolean(
      (initial.province && !province) ||
      (initial.city && !city) ||
      (initial.district && !city?.children?.some((node) => node.name === initial.district)),
    );
  });
  const controlled = value !== undefined;
  const state: AddressValue = controlled ? parseValue(value) : localState;

  // Derived city and district options
  const provinceNode = useMemo(
    () => regions.find((p) => p.name === state.province),
    [regions, state.province],
  );

  const cityNodes: RegionNode[] = provinceNode?.children ?? [];

  const cityNode = useMemo(
    () => cityNodes.find((c) => c.name === state.city),
    [cityNodes, state.city],
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
    'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-sm shadow-sm ' +
    'focus:outline-none focus-visible:shadow-focus ' +
    'disabled:cursor-not-allowed disabled:bg-subtle disabled:text-text-2';

  const textareaBase =
    'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-sm shadow-sm resize-none ' +
    'focus:outline-none focus-visible:shadow-focus ' +
    'disabled:cursor-not-allowed disabled:bg-subtle disabled:text-text-2';

  // Hidden form value - only serialize non-empty state
  const hasValue = state.province || state.city || state.district || state.detail;
  const hiddenValue = hasValue ? JSON.stringify(state) : '';

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      error={meta.showError ? String(meta.meta.error ?? '') : undefined}
      className={`space-y-3 ${className}`}
    >
      {allowManualEntry && (
        <div>
          <label className="inline-flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={manualEntry}
              disabled={!isInteractive}
              onChange={(event) => {
                setManualEntry(event.target.checked);
                if (!event.target.checked)
                  updateState({ province: '', city: '', district: '', detail: state.detail });
              }}
            />
            {text('manual')}
          </label>
          <p className="text-text-2 mt-1 text-xs">{text('manualHelp')}</p>
        </div>
      )}
      {/* Province / City / District row */}
      {manualEntry ? (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
          {(['province', 'city', 'district'] as const).map((key) => (
            <div key={key} className="min-w-0 space-y-1">
              <label htmlFor={`${controlId}-${key}`} className="text-text-2 text-xs font-medium">
                {text(key)}
              </label>
              <input
                id={`${controlId}-${key}`}
                type="text"
                aria-label={text(key)}
                placeholder={text(key)}
                value={state[key]}
                maxLength={100}
                disabled={!isInteractive}
                className={selectBase}
                onChange={(event) => updateState({ ...state, [key]: event.target.value })}
              />
            </div>
          ))}
        </div>
      ) : (
        <div className="grid grid-cols-3 gap-2">
          {/* Province */}
          <div className="min-w-0 space-y-1">
            <label htmlFor={`${controlId}-province`} className="text-text-2 text-xs font-medium">
              {text('province')}
            </label>
            <select
              id={`${controlId}-province`}
              data-testid={`address-province-${name}`}
              aria-label={text('province')}
              value={state.province}
              onChange={handleProvinceChange}
              disabled={!isInteractive}
              className={selectBase}
            >
              <option value="">{text('chooseProvince')}</option>
              {regions.map((p) => (
                <option key={p.code} value={p.name}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>

          {/* City */}
          <div className="min-w-0 space-y-1">
            <label htmlFor={`${controlId}-city`} className="text-text-2 text-xs font-medium">
              {text('city')}
            </label>
            <select
              id={`${controlId}-city`}
              data-testid={`address-city-${name}`}
              aria-label={text('city')}
              value={state.city}
              onChange={handleCityChange}
              disabled={!isInteractive || !state.province}
              className={selectBase}
            >
              <option value="">{text('chooseCity')}</option>
              {cityNodes.map((c) => (
                <option key={c.code} value={c.name}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>

          {/* District */}
          <div className="min-w-0 space-y-1">
            <label htmlFor={`${controlId}-district`} className="text-text-2 text-xs font-medium">
              {text('district')}
            </label>
            <select
              id={`${controlId}-district`}
              data-testid={`address-district-${name}`}
              aria-label={text('district')}
              value={state.district}
              onChange={handleDistrictChange}
              disabled={!isInteractive || !state.city}
              className={selectBase}
            >
              <option value="">
                {text('chooseDistrict')}
                {requireDistrict ? ' *' : ''}
              </option>
              {districtNodes.map((d) => (
                <option key={d.code} value={d.name}>
                  {d.name}
                </option>
              ))}
            </select>
          </div>
        </div>
      )}

      {state.province && state.city && state.district && (
        <div className="text-text-2 text-xs">
          {[state.province, state.city, state.district].join(' / ')}
        </div>
      )}

      {/* Detail address textarea */}
      {showDetailAddress && (
        <div className="space-y-1">
          <label htmlFor={`${controlId}-detail`} className="text-text-2 text-xs font-medium">
            {text('detail')}
          </label>
          <textarea
            id={`${controlId}-detail`}
            data-testid={`address-detail-${name}`}
            aria-label={text('detail')}
            rows={2}
            value={state.detail}
            onChange={handleDetailChange}
            disabled={!isInteractive}
            placeholder={detailPlaceholder ?? text('detailPlaceholder')}
            maxLength={detailMaxLength}
            className={textareaBase}
          />
        </div>
      )}

      {/* Hidden input for form submission */}
      <input type="hidden" name={name} value={hiddenValue} />
    </FieldBase>
  );
};

export default AddressField;
