/**
 * B2b batch3 — ProcessPicker (remote-data picker)
 *
 * Ported byte-equivalent from
 * bpmn-designer/components/property-editors/ProcessPicker.tsx (100 LOC).
 *
 * Real remote endpoint (grep-verified):
 *   - GET /api/bpm/process-definitions/deployed
 *     → { data: ProcessDefinition[] } or { data: { records: ProcessDefinition[] } }
 *
 * Used by CallActivityEditor to pick a calledProcessKey.
 */

import { useEffect, useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

export interface ProcessDefinition {
  pid: string;
  processKey: string;
  processName: string;
  description?: string;
  version: number;
  status: string;
}

export interface ProcessPickerProps {
  value: string; // processKey
  onChange: (processKey: string) => void;
  placeholder?: string;
}

export function ProcessPicker({ value, onChange, placeholder }: ProcessPickerProps) {
  const { t } = useI18n();
  const [definitions, setDefinitions] = useState<ProcessDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    get('/api/bpm/process-definitions/deployed')
      .then((res) => {
        if (cancelled) return;
        if (!ResultHelper.isSuccess(res)) return;
        const data = Array.isArray(res.data) ? res.data : (res.data as any)?.records || [];
        setDefinitions(data as ProcessDefinition[]);
      })
      // CATCH: non-transactional HTTP fetch, safe to handle
      .catch(() => {
        if (!cancelled) setDefinitions([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(() => {
    if (!search.trim()) return definitions;
    const q = search.toLowerCase();
    return definitions.filter(
      (d) =>
        d.processName.toLowerCase().includes(q) || d.processKey.toLowerCase().includes(q),
    );
  }, [definitions, search]);

  return (
    <div className="space-y-2" data-testid="bpm-sdk-process-picker">
      {/* Search input */}
      <input
        type="text"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
        placeholder={t('bpmn.callactivity.searchProcess')}
        data-testid="bpm-sdk-process-picker-search"
      />

      {/* Select dropdown */}
      <select
        value={value || ''}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
        disabled={loading}
        data-testid="bpm-sdk-process-picker-select"
      >
        <option value="">{placeholder || t('bpmn.callactivity.selectProcess')}</option>
        {loading && <option disabled>{t('bpmn.common.loading')}</option>}
        {filtered.map((def) => (
          <option key={def.processKey} value={def.processKey}>
            {def.processName} ({def.processKey}) v{def.version}
          </option>
        ))}
      </select>

      {!loading && definitions.length === 0 && (
        <p className="text-xs text-gray-500" data-testid="bpm-sdk-process-picker-empty">
          {t('bpmn.callactivity.noDeployedProcess')}
        </p>
      )}
    </div>
  );
}
