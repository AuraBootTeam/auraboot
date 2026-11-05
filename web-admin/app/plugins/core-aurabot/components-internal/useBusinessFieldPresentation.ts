import { useEffect, useMemo, useState } from 'react';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { useDictCache } from '~/framework/meta/rendering/pages/hooks/useDictCache';

type Field = {
  code: string;
  displayName?: string;
  dictCode?: string;
  options?: Array<{ value: unknown; label?: string }>;
  refTarget?: { targetEntity?: string; targetField?: string; valueField?: string };
};

/** Read presentation metadata through the caller's normal permission boundary. */
export function useBusinessFieldPresentation(modelCode?: string, records: Record<string, unknown>[] = []) {
  const { locale } = useI18n();
  const [fields, setFields] = useState<Field[]>([]);
  const [references, setReferences] = useState<Record<string, string>>({});
  useEffect(() => {
    let active = true;
    setFields([]);
    if (modelCode && /^[a-z][a-z0-9_]*$/.test(modelCode)) {
      get<Field[]>(`/api/dynamic/${encodeURIComponent(modelCode)}/field-meta`).then((response) => {
        if (active && ResultHelper.isSuccess(response) && Array.isArray(response.data)) setFields(response.data);
      }).catch(() => { /* Existing labels remain usable when metadata is not readable. */ });
    }
    return () => { active = false; };
  }, [modelCode, locale]);
  const byCode = useMemo(() => new Map(fields.map((field) => [field.code, field])), [fields]);
  const dictionaries = useDictCache({ dictCodes: fields.flatMap((field) => field.dictCode ? [field.dictCode] : []) });
  const referenceRequests = new Map<string, { model: string; pid: string; display: string }>();
  for (const field of fields) {
    const target = field.refTarget;
    if (!target?.targetEntity || !target.targetField || target.valueField !== 'pid') continue;
    for (const row of records) {
      const value = row[field.code];
      if (row[`${field.code}_display`] != null || typeof value !== 'string' || !/^[0-9A-HJKMNP-TV-Z]{26}$/i.test(value)) continue;
      referenceRequests.set(`${target.targetEntity}:${value}:${target.targetField}`, {
        model: target.targetEntity, pid: value, display: target.targetField,
      });
    }
  }
  const referenceKey = JSON.stringify([...referenceRequests]);
  useEffect(() => {
    let active = true;
    setReferences({});
    const requests = JSON.parse(referenceKey) as Array<[string, { model: string; pid: string; display: string }]>;
    Promise.all(requests.map(async ([key, target]) => {
      try {
        const response = await get<Record<string, unknown>>(`/api/dynamic/${encodeURIComponent(target.model)}/${encodeURIComponent(target.pid)}`);
        const value = ResultHelper.isSuccess(response) ? response.data?.[target.display] : undefined;
        return typeof value === 'string' && value ? [key, value] : null;
      } catch { return null; }
    })).then((values) => {
      if (active) setReferences(Object.fromEntries(values.filter((value): value is string[] => value !== null)));
    });
    return () => { active = false; };
  }, [referenceKey, locale]);
  return {
    label: (code: string) => byCode.get(code)?.displayName,
    value: (code: string, value: unknown, row: Record<string, unknown>) => {
      const field = byCode.get(code);
      const target = field?.refTarget;
      const reference = target && references[`${target.targetEntity}:${value}:${target.targetField}`];
      if (reference) return reference;
      if (row[`${code}_display`] != null) return String(row[`${code}_display`]);
      if (field?.dictCode) {
        const label = dictionaries.getDictLabel(field.dictCode, String(value));
        if (label) return label;
      }
      const option = Array.isArray(field?.options)
        ? field.options.find((option) => String(option.value) === String(value)) : undefined;
      return option?.label ?? value;
    },
  };
}
