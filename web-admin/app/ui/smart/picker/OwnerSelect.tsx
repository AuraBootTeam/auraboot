import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Users, ChevronDown, Check, X, Loader2 } from 'lucide-react';
import { fetchTeams, type Team } from '~/shared/services/teamService';
import { UserSelect } from './UserSelect';

/**
 * OwnerSelect — generic record-owner picker for the platform `owner_id` field.
 *
 * Paired with a sibling `owner_type` field (dict `owner_type`: user | team). This
 * control reads the current `owner_type` from the form context and renders the
 * matching picker — reusing {@link UserSelect} for `user`, and a team dropdown
 * (backed by `ab_team` via `/api/org/teams`) for `team`. It emits the selected
 * pid into `owner_id`. When `owner_type` changes, the stale `owner_id` is cleared.
 *
 * Generic by design: any model that binds `owner_type` + `owner_id` can use
 * `component: "ownerselect"` on the `owner_id` field — no CRM specifics here.
 */
export interface OwnerSelectProps {
  name: string;
  value?: string;
  onChange?: (value: string | undefined) => void;
  context?: { record?: Record<string, unknown>; data?: Record<string, unknown> };
  disabled?: boolean;
  readOnly?: boolean;
  required?: boolean;
  placeholder?: string;
  /** Override for the sibling field that holds the owner type. Defaults to `owner_type`. */
  ownerTypeField?: string;
}

export const OwnerSelect: React.FC<OwnerSelectProps> = ({
  name,
  value,
  onChange,
  context,
  disabled = false,
  readOnly = false,
  required = false,
  placeholder,
  ownerTypeField = 'owner_type',
}) => {
  const record = (context?.record || context?.data || {}) as Record<string, unknown>;
  const ownerType = String(record[ownerTypeField] ?? '');

  // Clear the stale owner_id when the owner_type changes by user action (not on the
  // initial mount of an existing record, which already has a matching owner_id).
  const mountedType = useRef<string | null>(null);
  useEffect(() => {
    if (mountedType.current === null) {
      mountedType.current = ownerType;
      return;
    }
    if (mountedType.current !== ownerType) {
      mountedType.current = ownerType;
      if (value) onChange?.(undefined);
    }
  }, [ownerType, value, onChange]);

  // ---- team mode data ----
  const [teams, setTeams] = useState<Team[]>([]);
  const [loadingTeams, setLoadingTeams] = useState(false);
  const [teamOpen, setTeamOpen] = useState(false);
  const teamRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (ownerType !== 'team') return;
    let cancelled = false;
    setLoadingTeams(true);
    fetchTeams()
      .then((list) => {
        if (!cancelled) setTeams(list);
      })
      .catch(() => {
        if (!cancelled) setTeams([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingTeams(false);
      });
    return () => {
      cancelled = true;
    };
  }, [ownerType]);

  useEffect(() => {
    if (!teamOpen) return;
    const handler = (e: MouseEvent) => {
      if (teamRef.current && !teamRef.current.contains(e.target as Node)) setTeamOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [teamOpen]);

  const selectedTeamName = useMemo(
    () => teams.find((tm) => tm.pid === value)?.name,
    [teams, value],
  );

  // ---- no type chosen yet ----
  if (!ownerType) {
    return (
      <div
        data-testid={`owner-select-pick-type-first-${name}`}
        className="rounded-card border-border bg-subtle text-text-3 flex min-h-[38px] w-full items-center border px-3 py-1.5 text-sm"
      >
        {placeholder || '请先选择归属类型'}
      </div>
    );
  }

  // ---- user mode: reuse the proven member picker ----
  if (ownerType === 'user') {
    return (
      <div data-testid={`owner-select-${name}`}>
        <UserSelect
          name={name}
          value={value}
          onChange={(v) => onChange?.(Array.isArray(v) ? v[0] : v)}
          disabled={disabled || readOnly}
          required={required}
          placeholder={placeholder || '搜索并选择用户'}
        />
      </div>
    );
  }

  // ---- team mode: ab_team dropdown ----
  const isDisabled = disabled || readOnly;
  return (
    <div data-testid={`owner-select-${name}`} ref={teamRef} className="relative">
      <div
        data-testid={`owner-select-team-trigger-${name}`}
        className={`rounded-card flex min-h-[38px] w-full items-center justify-between border px-3 py-1.5 shadow-sm transition-all ${
          isDisabled
            ? 'border-border bg-subtle cursor-not-allowed'
            : teamOpen
              ? 'border-accent bg-panel ring-2 ring-accent-weak'
              : 'border-border-strong bg-panel hover:border-border-strong cursor-pointer'
        }`}
        onClick={() => !isDisabled && setTeamOpen(!teamOpen)}
      >
        <span className={`truncate text-sm ${selectedTeamName ? 'text-text' : 'text-text-3'}`}>
          {selectedTeamName || placeholder || '选择团队'}
        </span>
        <span className="flex flex-shrink-0 items-center gap-1">
          {value && !isDisabled && (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onChange?.(undefined);
              }}
              className="rounded-pill text-text-3 hover:bg-hover hover:text-text-2 flex h-5 w-5 items-center justify-center transition-colors"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          )}
          <Users className="text-text-3 h-4 w-4" />
        </span>
      </div>

      {teamOpen && !isDisabled && (
        <div className="rounded-card border-border bg-panel absolute z-50 mt-1.5 max-h-60 w-full overflow-y-auto border py-1 shadow-lg">
          {loadingTeams ? (
            <div className="text-text-3 flex items-center justify-center py-6">
              <Loader2 className="text-accent h-5 w-5 animate-spin" />
            </div>
          ) : teams.length === 0 ? (
            <div className="text-text-3 px-3 py-6 text-center text-sm">暂无团队</div>
          ) : (
            teams.map((tm) => {
              const selected = tm.pid === value;
              return (
                <div
                  key={tm.pid}
                  data-testid={`owner-select-team-option-${name}-${tm.pid}`}
                  className={`rounded-control mx-1 flex cursor-pointer items-center gap-2 px-2 py-2 text-sm transition-colors ${
                    selected ? 'bg-accent-weak text-accent' : 'text-text-2 hover:bg-subtle'
                  }`}
                  onClick={() => {
                    onChange?.(tm.pid);
                    setTeamOpen(false);
                  }}
                >
                  <Users className="text-text-3 h-4 w-4 flex-shrink-0" />
                  <span className="flex-1 truncate">{tm.name}</span>
                  {selected && <Check className="text-accent h-4 w-4 flex-shrink-0" />}
                </div>
              );
            })
          )}
        </div>
      )}
    </div>
  );
};

export default OwnerSelect;
