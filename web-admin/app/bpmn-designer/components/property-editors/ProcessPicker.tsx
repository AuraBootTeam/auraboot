/**
 * Search-select component for choosing a deployed BPM process definition.
 * Used in CallActivityEditor to pick a calledProcessKey.
 *
 * API: GET /api/bpm/process-definitions/deployed
 * Response: { data: [{ pid, processKey, processName, description, version, status }] }
 */

import { useEffect, useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { get } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

interface ProcessDefinition {
  pid: string;
  processKey: string;
  processName: string;
  description?: string;
  version: number;
  status: string;
}

interface ProcessPickerProps {
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
        d.processName.toLowerCase().includes(q) ||
        d.processKey.toLowerCase().includes(q),
    );
  }, [definitions, search]);

  return (
    <div className="space-y-2">
      {/* Search input */}
      <input
        type="text"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
        placeholder={t('bpmn.callactivity.searchProcess')}
      />

      {/* Select dropdown */}
      <select
        value={value || ''}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
        disabled={loading}
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
        <p className="text-xs text-gray-500">{t('bpmn.callactivity.noDeployedProcess')}</p>
      )}
    </div>
  );
}
