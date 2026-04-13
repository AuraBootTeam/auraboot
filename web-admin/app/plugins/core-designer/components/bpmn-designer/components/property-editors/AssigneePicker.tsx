/**
 * Lightweight user/role/team picker for the BPMN designer property panel.
 * Standalone alternative to SmartField-based UserSelect — no dependency on
 * useSmartFieldContract / useSmartFieldMeta.
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { Search, X, User, Shield, Building2, Loader2, Plus } from 'lucide-react';
import { useI18n } from '~/contexts/I18nContext';
import { get, post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface AssigneePickerProps {
  type: 'user' | 'role' | 'dept';
  value: string[];
  onChange: (ids: string[]) => void;
  placeholder?: string;
}

interface OptionItem {
  id: string;
  label: string;
  secondary?: string;
}

// API response shapes
interface MemberRecord {
  displayName: string;
  user: { pid: string; username: string; email: string; realName: string; avatar?: string };
}

interface RoleRecord {
  pid: string;
  code: string;
  name: string;
}

interface TeamRecord {
  pid: string;
  name: string;
  code: string;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const DEBOUNCE_MS = 300;

const TYPE_META: Record<AssigneePickerProps['type'], { icon: typeof User; labelKey: string }> = {
  user: { icon: User, labelKey: 'bpmn.prop.assignee.typeUser' },
  role: { icon: Shield, labelKey: 'bpmn.prop.assignee.typeRole' },
  dept: { icon: Building2, labelKey: 'bpmn.prop.assignee.typeTeam' },
};

// ---------------------------------------------------------------------------
// Data fetching helpers
// ---------------------------------------------------------------------------

async function fetchUsers(keyword: string): Promise<OptionItem[]> {
  const result = await post<{ records: MemberRecord[] }>('/tenant/members/search', {
    keyword,
    pageNum: 1,
    pageSize: 20,
  });
  if (!ResultHelper.isSuccess(result) || !result.data) return [];
  return (result.data.records ?? []).map((r) => ({
    id: r.user.pid,
    label: r.displayName || r.user.realName || r.user.username,
    secondary: r.user.email,
  }));
}

async function fetchRoles(_keyword: string): Promise<OptionItem[]> {
  const result = await get<RoleRecord[]>('/roles/all');
  if (!ResultHelper.isSuccess(result) || !result.data) return [];
  return result.data
    .filter((r) => !_keyword || r.name.toLowerCase().includes(_keyword.toLowerCase()) || r.code.toLowerCase().includes(_keyword.toLowerCase()))
    .map((r) => ({ id: r.pid, label: r.name, secondary: r.code }));
}

async function fetchTeams(_keyword: string): Promise<OptionItem[]> {
  const result = await get<TeamRecord[]>('/org/teams');
  if (!ResultHelper.isSuccess(result) || !result.data) return [];
  return result.data
    .filter((r) => !_keyword || r.name.toLowerCase().includes(_keyword.toLowerCase()))
    .map((r) => ({ id: r.pid, label: r.name }));
}

const FETCHERS: Record<AssigneePickerProps['type'], (kw: string) => Promise<OptionItem[]>> = {
  user: fetchUsers,
  role: fetchRoles,
  dept: fetchTeams,
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function AssigneePicker({ type, value, onChange, placeholder }: AssigneePickerProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [options, setOptions] = useState<OptionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const selectedSet = new Set(value);
  const meta = TYPE_META[type];
  const Icon = meta.icon;

  // -- Fetch on open / keyword change --
  const doFetch = useCallback(
    async (kw: string) => {
      setLoading(true);
      const items = await FETCHERS[type](kw);
      setOptions(items);
      setLoading(false);
    },
    [type],
  );

  useEffect(() => {
    if (!open) return;
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => doFetch(keyword), DEBOUNCE_MS);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [keyword, open, doFetch]);

  // -- Click outside --
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  // -- Toggle selection --
  const toggle = (id: string) => {
    if (selectedSet.has(id)) {
      onChange(value.filter((v) => v !== id));
    } else {
      onChange([...value, id]);
    }
  };

  const remove = (id: string) => onChange(value.filter((v) => v !== id));

  // Build a label map from current options so selected tags can display names
  const labelMap = new Map(options.map((o) => [o.id, o]));

  return (
    <div ref={containerRef} className="relative">
      {/* Selected tags */}
      {value.length > 0 && (
        <div className="mb-1.5 flex flex-wrap gap-1">
          {value.map((id) => {
            const item = labelMap.get(id);
            return (
              <span
                key={id}
                className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs text-blue-700"
              >
                <Icon className="h-3 w-3" />
                {item ? item.label : id}
                <button
                  type="button"
                  onClick={() => remove(id)}
                  className="ml-0.5 rounded-full p-0.5 hover:bg-blue-100"
                  aria-label={t('bpmn.common.remove')}
                >
                  <X className="h-3 w-3" />
                </button>
              </span>
            );
          })}
        </div>
      )}

      {/* Add button */}
      <button
        type="button"
        onClick={() => {
          setOpen(!open);
          if (!open) {
            setKeyword('');
          }
        }}
        className="flex items-center gap-1 rounded-md border border-dashed border-gray-300 px-2 py-1 text-xs text-gray-500 hover:border-gray-400 hover:text-gray-700"
      >
        <Plus className="h-3.5 w-3.5" />
        {placeholder ?? t('bpmn.prop.assignee.addPlaceholder', { type: t(meta.labelKey) })}
      </button>

      {/* Dropdown */}
      {open && (
        <div className="absolute left-0 z-50 mt-1 w-full min-w-[220px] rounded-md border border-gray-200 bg-white shadow-lg">
          {/* Search input */}
          <div className="flex items-center gap-1.5 border-b border-gray-100 px-2 py-1.5">
            <Search className="h-3.5 w-3.5 shrink-0 text-gray-400" />
            <input
              type="text"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder={t('bpmn.common.search')}
              className="w-full border-none bg-transparent text-sm outline-none placeholder:text-gray-400"
              autoFocus
            />
          </div>

          {/* Options list */}
          <div className="max-h-48 overflow-y-auto py-1">
            {loading && (
              <div className="flex items-center justify-center py-4 text-xs text-gray-400">
                <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
                {t('bpmn.common.loading')}
              </div>
            )}

            {!loading && options.length === 0 && (
              <div className="py-4 text-center text-xs text-gray-400">{t('bpmn.common.noResults')}</div>
            )}

            {!loading &&
              options.map((item) => {
                const checked = selectedSet.has(item.id);
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => toggle(item.id)}
                    className={`flex w-full items-center gap-2 px-2 py-1.5 text-left text-sm hover:bg-gray-50 ${
                      checked ? 'bg-blue-50/50' : ''
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      readOnly
                      className="pointer-events-none h-3.5 w-3.5 rounded border-gray-300 text-blue-600"
                    />
                    <div className="min-w-0 flex-1">
                      <span className="block truncate text-sm text-gray-700">{item.label}</span>
                      {item.secondary && (
                        <span className="block truncate text-xs text-gray-400">{item.secondary}</span>
                      )}
                    </div>
                  </button>
                );
              })}
          </div>
        </div>
      )}
    </div>
  );
}
